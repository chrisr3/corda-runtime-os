package net.corda.p2p.gateway.messaging.http

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SniCompletionEvent
import io.netty.handler.ssl.SslHandshakeCompletionEvent
import net.corda.messaging.api.subscription.LifeCycle
import net.corda.p2p.gateway.messaging.ReceivedMessage
import net.corda.p2p.gateway.messaging.SslConfiguration
import net.corda.p2p.gateway.messaging.toHostAndPort
import net.corda.v5.base.util.NetworkHostAndPort
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.PublishSubject
import java.lang.IllegalStateException
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLException
import kotlin.concurrent.withLock

/**
 * The [HttpServer] is responsible for opening a socket listener on the configured port in order to receive and handle
 * multiple HTTP(S) connections.
 *
 * The server hosts only one endpoint which can be used for POST requests. In the future, it may host versioned endpoints.
 *
 * The body of the POST requests should contain serialised (and possibly encrypted) Corda P2P messages. The server is not
 * responsible for validating these messages, only the request headers. Once a request is checks out, its body is sent upstream
 * and a response is sent back to the client. The response body is empty unless it follows a session handshake request,
 * in which case the body will contain additional information.
 *
 * The server provides two observables [onReceive] and [onConnection] which upstream services can subscribe to receive
 * updates on important events.
 */
class HttpServer(private val hostAddress: NetworkHostAndPort, private val sslConfig: SslConfiguration) : LifeCycle {

    private val logger = LoggerFactory.getLogger(HttpServer::class.java)

    companion object {
        /**
         * Default number of thread to use for the worker group
         */
        private const val NUM_SERVER_THREADS = 4
    }

    private val lock = ReentrantLock()
    @Volatile
    private var stopping: Boolean = false
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var serverChannel: Channel? = null
    private val clientChannels = ConcurrentHashMap<InetSocketAddress, SocketChannel>()

    private val _onReceive = PublishSubject.create<ReceivedMessage>().toSerialized()
    val onReceive: Observable<ReceivedMessage>
        get() = _onReceive

    private val _onConnection = PublishSubject.create<ConnectionChangeEvent>().toSerialized()
    val onConnection: Observable<ConnectionChangeEvent>
        get() = _onConnection

    /**
     * @throws BindException if the server cannot bind to the address provided in the constructor
     */
    override  fun start() {
        lock.withLock {
            logger.info("Starting HTTP Server")
            bossGroup = NioEventLoopGroup(1)
            //TODO: should allow an arbitrary value read from Corda config perhaps
            workerGroup = NioEventLoopGroup(NUM_SERVER_THREADS)

            val server = ServerBootstrap()
            server.group(bossGroup, workerGroup).channel(NioServerSocketChannel::class.java)
//                .handler(LoggingHandler(LogLevel.INFO))
                .childHandler(ServerChannelInitializer(this))
            logger.info("Trying to bind to ${hostAddress.port}")
            val channelFuture = server.bind(hostAddress.host, hostAddress.port).sync()
            logger.info("Listening on port ${hostAddress.port}")
            serverChannel = channelFuture.channel()
        }
    }

    override  fun stop() {
        lock.withLock {
            try {
                logger.info("Stopping HTTP server")
                stopping = true
                serverChannel?.apply { close() }
                serverChannel = null

                workerGroup?.shutdownGracefully()
                workerGroup?.terminationFuture()?.sync()

                bossGroup?.shutdownGracefully()
                bossGroup?.terminationFuture()?.sync()

                workerGroup = null
                bossGroup = null
            } finally {
                stopping = false
                logger.info("HTTP server stopped")
            }
        }
    }

    /**
     * Writes the given message to the channel corresponding to the recipient address. This method should be called
     * by upstream services in order to send an HTTP response
     * @param message
     * @param destination
     * @throws IllegalStateException if the connection to the peer is not active. This can happen because either the peer
     * has closed it or the server is stopped
     */
    @Throws(IllegalStateException::class)
    fun write(message: ByteArray, destination: NetworkHostAndPort) {
        val channel = clientChannels[InetSocketAddress(destination.host, destination.port)]
        if (channel == null) {
            throw IllegalStateException("Connection to $destination not active")
        } else {
            logger.info("Writing HTTP response to channel $channel")
            writeResponse(message, channel)
            logger.info("Done writing HTTP response to channel $channel")
        }
    }

    private fun writeResponse(responseContent: ByteArray, ch: SocketChannel) {
        val status = HttpResponseStatus.OK
        val response = HttpHelper.createResponse(responseContent, status)

        ch.writeAndFlush(response)
    }

    private class ServerChannelInitializer(private val parent: HttpServer) : ChannelInitializer<SocketChannel>() {

        private val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())

        init {
            parent.sslConfig.run {
                keyManagerFactory.init(this.keyStore, this.keyStorePassword.toCharArray())
            }
        }

        override fun initChannel(ch: SocketChannel) {
            val pipeline = ch.pipeline()
            pipeline.addLast("sslHandler", createServerSslHandler(keyManagerFactory))
            pipeline.addLast(HttpRequestDecoder())
            pipeline.addLast(HttpResponseEncoder())
            pipeline.addLast(HttpServerChannelHandler(
                onOpen = { channel, change ->
                    parent.run {
                        clientChannels[channel.remoteAddress()] = channel
                        _onConnection.onNext(change)
                    }
                },
                onClose = { channel, change ->
                    parent.run {
                        clientChannels.remove(channel.remoteAddress())
                        _onConnection.onNext(change)
                    }
                },
                onReceive = { msg ->
                    parent._onReceive.onNext(msg)
                }
            ))
        }
    }

    private class HttpServerChannelHandler(private val onOpen: (SocketChannel, ConnectionChangeEvent) -> Unit,
                                           private val onClose: (SocketChannel, ConnectionChangeEvent) -> Unit,
                                           private val onReceive: (ReceivedMessage) -> Unit
    ) : SimpleChannelInboundHandler<HttpObject>() {

        private val logger = LoggerFactory.getLogger(HttpServerChannelHandler::class.java)

        private var requestBodyBuf: ByteBuf? = null

        /**
         * TODO: need to add validation of request headers and also payload. Build response based on that
         */
        override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
            if (msg is HttpRequest) {
                // This logging will be moved to debug or removed once everything is nice and done
                logger.info("Received HTTP request from ${ctx.channel().remoteAddress()}")
                logger.info("Protocol version: ${msg.protocolVersion()}")
                logger.info("Hostname: ${msg.headers()[HttpHeaderNames.HOST]?:"unknown"}")
                logger.info("Request URI: ${msg.uri()}")
                logger.info("Content length: ${msg.headers()[HttpHeaderNames.CONTENT_LENGTH]?:"unknown"}")
                // initialise byte array to read the request into
                requestBodyBuf = ctx.alloc().buffer(msg.headers()[HttpHeaderNames.CONTENT_LENGTH].toInt())

                if (HttpUtil.is100ContinueExpected(msg)) {
                    send100Continue(ctx)
                }
            }

            if (msg is HttpContent) {
                val content = msg.content()
                if (content.isReadable) {
                    logger.info("Reading request content into local buffer of size ${content.readableBytes()}")
                    content.readBytes(requestBodyBuf, content.readableBytes())
//                    logger.info(requestBodyBuf!!.toString(Charsets.UTF_8))
                }
            }

            if (msg is LastHttpContent) {
                logger.info("Read end of request body")
                val channel = ctx.channel()
                val sourceAddress = channel.remoteAddress() as InetSocketAddress
                val targetAddress = channel.localAddress() as InetSocketAddress
                val returnByteArray = ByteArray(requestBodyBuf!!.readableBytes())
                requestBodyBuf!!.readBytes(returnByteArray)
                onReceive(ReceivedMessage(returnByteArray, sourceAddress.toHostAndPort(), targetAddress.toHostAndPort()))
                // Normally response would be sent right after but that operation is now delegated to upstream
                requestBodyBuf?.release()
            }
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            val ch = ctx.channel()
            logger.info("New client connection ${ch.id()} from ${ch.remoteAddress()} to ${ch.localAddress()}")
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            val ch = ctx.channel()
            logger.info("Closed client connection ${ch.id()} from ${ch.remoteAddress()} to ${ch.localAddress()}")
            requestBodyBuf?.let {
                if (it.refCnt() > 0)
                    it.release()
            }
            val remoteAddress = (ch.remoteAddress() as InetSocketAddress).let { NetworkHostAndPort(it.hostName, it.port) }
            onClose(ch as SocketChannel, ConnectionChangeEvent(remoteAddress, false))
            ctx.fireChannelInactive()
        }

        //TODO: a bunch of this code (except channel read) is duplicated and should probably be reused
        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            when (evt) {
                is SniCompletionEvent -> {
                    logger.warn("NOT YET IMPLEMENTED LOL")
                }

                is SslHandshakeCompletionEvent -> {
                    if (evt.isSuccess) {
                        val ch = ctx.channel()
                        val remoteAddress = (ch.remoteAddress() as InetSocketAddress).let { NetworkHostAndPort(it.hostName, it.port) }
                        logger.info("Handshake with ${ctx.channel().remoteAddress()} successful")
                        onOpen(ch as SocketChannel, ConnectionChangeEvent(remoteAddress, true))
                    } else {
                        val cause = evt.cause()
                        if (cause is ClosedChannelException) {
                            logger.warn("SSL handshake closed early")
                        } else if (cause is SSLException && cause.message == "handshake timed out") {
                            logger.warn("SSL handshake timed out")
                        }
                        logger.error("Handshake failure ${evt.cause().message}")
                        ctx.close()
                    }
                }
            }
        }

        override fun channelReadComplete(ctx: ChannelHandlerContext) {
            // Nothing more to read from the transport in this event-loop run. Simply flush
            ctx.flush()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            ctx.close()
        }

        private fun send100Continue(ctx: ChannelHandlerContext) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER)
            ctx.write(response)
        }
    }
}