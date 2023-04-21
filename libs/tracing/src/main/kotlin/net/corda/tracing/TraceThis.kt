package net.corda.tracing

import brave.Tracer
import brave.Tracing
import brave.baggage.BaggageField
import brave.baggage.BaggagePropagation
import brave.baggage.BaggagePropagationConfig
import brave.context.slf4j.MDCScopeDecorator
import brave.handler.SpanHandler
import brave.kafka.clients.KafkaTracing
import brave.propagation.B3Propagation
import brave.propagation.ThreadLocalCurrentTraceContext
import brave.sampler.Sampler
import brave.servlet.TracingFilter
import io.javalin.core.JavalinConfig
import org.eclipse.jetty.servlet.FilterHolder
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.brave.ZipkinSpanHandler
import zipkin2.reporter.urlconnection.URLConnectionSender
import java.util.*
import javax.servlet.DispatcherType

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


fun <T> traceThis(block: () -> T): T {
    try {

        return block()
    } finally {

    }
}

fun configureJavalinForTracing(config: JavalinConfig) {
    config.configureServletContextHandler { sch ->
        sch.addFilter(
            FilterHolder(TracingFilter.create(tracing)),
            "/*",
            EnumSet.of(DispatcherType.INCLUDE, DispatcherType.REQUEST)
        )
    }
}