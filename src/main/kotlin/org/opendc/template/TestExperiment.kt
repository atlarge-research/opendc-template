package org.opendc.template

import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricProducer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeCapabilitiesFilter
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.weights.ProvisionedCoresWeigher
import org.opendc.compute.simulator.SimHost
import org.opendc.format.environment.sc18.Sc18EnvironmentReader
import org.opendc.format.trace.gwf.GwfTraceReader
import org.opendc.harness.dsl.Experiment
import org.opendc.harness.dsl.anyOf
import org.opendc.simulator.compute.SimSpaceSharedHypervisorProvider
import org.opendc.simulator.core.runBlockingSimulation
import org.opendc.telemetry.sdk.toOtelClock
import org.opendc.workflow.service.WorkflowService
import org.opendc.workflow.service.scheduler.WorkflowSchedulerMode
import org.opendc.workflow.service.scheduler.job.NullJobAdmissionPolicy
import org.opendc.workflow.service.scheduler.job.SubmissionTimeJobOrderPolicy
import org.opendc.workflow.service.scheduler.task.NullTaskEligibilityPolicy
import org.opendc.workflow.service.scheduler.task.SubmissionTimeTaskOrderPolicy
import java.io.FileInputStream
import kotlin.math.max

class TestExperiment : Experiment("test") {
    /**
     * The environments to test.
     */
    private val env by anyOf("environment.json")

    /**
     * The traces to test.
     */
    private val trace by anyOf("trace.gwf")

    override fun doRun(repeat: Int) = runBlockingSimulation {
        val meterProvider: MeterProvider = SdkMeterProvider
            .builder()
            .setClock(clock.toOtelClock())
            .build()

        val hosts = Sc18EnvironmentReader(FileInputStream(env))
            .use { it.read() }
            .map { def ->
                SimHost(
                    def.uid,
                    def.name,
                    def.model,
                    def.meta,
                    coroutineContext,
                    clock,
                    meterProvider.get("opendc-compute-simulator"),
                    SimSpaceSharedHypervisorProvider()
                )
            }

        val meter = meterProvider.get("opendc-compute")
        val computeScheduler = FilterScheduler(
            filters = listOf(ComputeFilter(), ComputeCapabilitiesFilter()),
            weighers = listOf(ProvisionedCoresWeigher() to -1.0)
        )
        val compute = ComputeService(coroutineContext, clock, meter, computeScheduler, schedulingQuantum = 1000)
        
        hosts.forEach { compute.addHost(it) }

        val scheduler = WorkflowService(
            coroutineContext,
            clock,
            meterProvider.get("opendc-workflow"),
            compute.newClient(),
            mode = WorkflowSchedulerMode.Batch(1000),
            jobAdmissionPolicy = NullJobAdmissionPolicy,
            jobOrderPolicy = SubmissionTimeJobOrderPolicy(),
            taskEligibilityPolicy = NullTaskEligibilityPolicy,
            taskOrderPolicy = SubmissionTimeTaskOrderPolicy(),
        )

        val reader = GwfTraceReader(FileInputStream(trace))
        var offset = Long.MIN_VALUE

        coroutineScope {
            while (reader.hasNext()) {
                val entry = reader.next()

                if (offset < 0) {
                    offset = entry.start - clock.millis()
                }

                delay(max(0, (entry.start - offset) - clock.millis()))
                launch {
                    scheduler.run(entry.workload)
                }
            }
        }

        hosts.forEach(SimHost::close)
        scheduler.close()
        compute.close()


        val metrics = collectMetrics(meterProvider as MetricProducer)

        println("Workflow trace finished...")
        println("Jobs: ${metrics.jobsFinished}/${metrics.jobsSubmitted}")
        println("Tasks: ${metrics.tasksFinished}/${metrics.tasksSubmitted}")
    }

    private class WorkflowMetrics {
        var jobsSubmitted = 0L
        var jobsActive = 0L
        var jobsFinished = 0L
        var tasksSubmitted = 0L
        var tasksActive = 0L
        var tasksFinished = 0L
    }

    /**
     * Collect the metrics of the workflow service.
     */
    private fun collectMetrics(metricProducer: MetricProducer): WorkflowMetrics {
        val metrics = metricProducer.collectAllMetrics().associateBy { it.name }
        val res = WorkflowMetrics()
        res.jobsSubmitted = metrics["jobs.submitted"]?.longSumData?.points?.last()?.value ?: 0
        res.jobsActive = metrics["jobs.active"]?.longSumData?.points?.last()?.value ?: 0
        res.jobsFinished = metrics["jobs.finished"]?.longSumData?.points?.last()?.value ?: 0
        res.tasksSubmitted = metrics["tasks.submitted"]?.longSumData?.points?.last()?.value ?: 0
        res.tasksActive = metrics["tasks.active"]?.longSumData?.points?.last()?.value ?: 0
        res.tasksFinished = metrics["tasks.finished"]?.longSumData?.points?.last()?.value ?: 0
        return res
    }
}