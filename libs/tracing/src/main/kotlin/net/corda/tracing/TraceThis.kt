package net.corda.tracing
//
//import brave.Tracer
//import brave.Tracing
//import brave.baggage.BaggageField
//import brave.baggage.BaggagePropagation
//import brave.baggage.BaggagePropagationConfig
//import brave.context.slf4j.MDCScopeDecorator
//import brave.propagation.B3Propagation
//import brave.propagation.ThreadLocalCurrentTraceContext
//import brave.sampler.Sampler
//import io.micrometer.tracing.CurrentTraceContext
//import io.micrometer.tracing.brave.bridge.BraveBaggageManager
//import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext
//import io.micrometer.tracing.brave.bridge.BraveTracer
//import zipkin2.reporter.AsyncReporter
//import zipkin2.reporter.brave.ZipkinSpanHandler
//import zipkin2.reporter.urlconnection.URLConnectionSender
//import java.util.*
//
//private val braveTracer: BraveTracer =
//    run {
//        val spanHandler = ZipkinSpanHandler
//            .create(AsyncReporter.create(URLConnectionSender.create("http://localhost:9411/api/v2/spans")))
//        val braveCurrentTraceContext = ThreadLocalCurrentTraceContext.newBuilder()
//            .addScopeDecorator(MDCScopeDecorator.get())
//            .build()
//        val bridgeContext: CurrentTraceContext = BraveCurrentTraceContext(braveCurrentTraceContext)
//        val tracing = Tracing.newBuilder().currentTraceContext(braveCurrentTraceContext).supportsJoin(false)
//            .localServiceName("my-service")
//            .traceId128Bit(true) // For Baggage to work you need to provide a list of fields to propagate
//            .propagationFactory(
//                BaggagePropagation.newFactoryBuilder(B3Propagation.FACTORY)
//                    .add(
//                        BaggagePropagationConfig.SingleBaggageField
//                            .remote(BaggageField.create("from_span_in_scope 1"))
//                    )
//                    .add(
//                        BaggagePropagationConfig.SingleBaggageField
//                            .remote(BaggageField.create("from_span_in_scope 2"))
//                    )
//                    .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("from_span")))
//                    .build()
//            )
//            .sampler(Sampler.ALWAYS_SAMPLE).addSpanHandler(spanHandler).build()
//        val braveTracer1: Tracer = tracing.tracer()
//        // For Baggage to work you need to provide a list of fields to propagate
//        BraveTracer(braveTracer1, bridgeContext, BraveBaggageManager())
//    }
//
//
//fun <T> traceThis(block: () -> T): T {
//    return block()
//}