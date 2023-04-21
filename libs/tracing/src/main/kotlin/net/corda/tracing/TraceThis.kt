package net.corda.tracing

import brave.Tracer
import brave.Tracing
import brave.baggage.BaggageField
import brave.baggage.BaggagePropagation
import brave.baggage.BaggagePropagationConfig
import brave.context.slf4j.MDCScopeDecorator
import brave.handler.SpanHandler
import brave.jakarta.servlet.TracingFilter
import brave.kafka.clients.KafkaTracing
import brave.propagation.B3Propagation
import brave.propagation.ThreadLocalCurrentTraceContext
import brave.sampler.Sampler
import io.javalin.Javalin
import jakarta.servlet.DispatcherType
//import org.apache.kafka.clients.producer.KafkaProducer
//import org.apache.kafka.clients.producer.ProducerRecord
import org.eclipse.jetty.servlet.FilterHolder
import org.slf4j.LoggerFactory
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.brave.ZipkinSpanHandler
import zipkin2.reporter.urlconnection.URLConnectionSender
import java.util.*

object a {
    private val spanHandler: SpanHandler = ZipkinSpanHandler
        .create(AsyncReporter.create(URLConnectionSender.create("http://localhost:9411/api/v2/spans")))
    private val braveCurrentTraceContext: ThreadLocalCurrentTraceContext = ThreadLocalCurrentTraceContext.newBuilder()
        .addScopeDecorator(MDCScopeDecorator.get())
        .build()
    val tracing: Tracing = Tracing.newBuilder().currentTraceContext(braveCurrentTraceContext).supportsJoin(false)
        .localServiceName("api-server")
        .traceId128Bit(true)
        .propagationFactory(
            BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
                .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("baggage_1")))
                .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("baggage_2")))
                .build()
        )
        .sampler(Sampler.ALWAYS_SAMPLE).addSpanHandler(spanHandler).build()
    private val tracer: Tracer = tracing.tracer()

    val kafkaTracing: KafkaTracing = KafkaTracing.newBuilder(tracing)
        .remoteServiceName("my-broker")
        .build()
}


fun <T> traceThis(block: () -> T): T {
    try {

        return block()
    } finally {

    }
}