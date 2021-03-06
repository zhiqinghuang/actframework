package act.job;

/*-
 * #%L
 * ACT Framework
 * %%
 * Copyright (C) 2014 - 2017 ActFramework
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import act.Act;
import act.Destroyable;
import act.app.App;
import act.app.AppServiceBase;
import act.app.AppThreadFactory;
import act.app.event.AppEventId;
import act.event.AppEventListenerBase;
import act.event.OnceEventListenerBase;
import act.mail.MailerContext;
import act.util.ProgressGauge;
import act.util.SimpleProgressGauge;
import org.joda.time.DateTime;
import org.joda.time.Seconds;
import org.osgl.$;
import org.osgl.Osgl;
import org.osgl.exception.NotAppliedException;
import org.osgl.logging.LogManager;
import org.osgl.logging.Logger;
import org.osgl.util.C;
import org.osgl.util.E;
import org.osgl.util.S;
import org.rythmengine.utils.Time;

import java.util.EventObject;
import java.util.Map;
import java.util.concurrent.*;

public class AppJobManager extends AppServiceBase<AppJobManager> {

    private static final Logger LOGGER = LogManager.get(AppJobManager.class);

    private ScheduledThreadPoolExecutor executor;
    private ConcurrentMap<String, Job> jobs = new ConcurrentHashMap<String, Job>();
    private ConcurrentMap<String, ScheduledFuture> scheduled = new ConcurrentHashMap<>();

    static String appEventJobId(AppEventId eventId) {
        return S.concat("__act_app__", eventId.toString().toLowerCase());
    }

    public AppJobManager(App app) {
        super(app);
        initExecutor(app);
        for (AppEventId appEventId : AppEventId.values()) {
            createAppEventListener(appEventId);
        }
    }

    @Override
    protected void releaseResources() {
        LOGGER.trace("release job manager resources");
        for (Job job : jobs.values()) {
            job.destroy();
        }
        jobs.clear();
        executor.getQueue().clear();
        executor.shutdownNow();
    }

    public <T> Future<T> now(Callable<T> callable) {
        return now(randomJobId(), callable);
    }

    public <T> Future<T> now(String jobId, final Callable<T> callable) {
        final Job job = wrap(jobId, callable);
        return executor().submit(new Callable<T>() {
            @Override
            public T call() throws Exception {
                job.run();
                if (null != job.callableException) {
                    throw job.callableException;
                }
                return (T) job.callableResult;
            }
        });
    }

    public void now(Runnable runnable) {
        now(randomJobId(), runnable);
    }

    public void now(String jobId, Runnable runnable) {
        executor().submit(wrap(jobId, runnable));
    }

    public String now($.Function<ProgressGauge, ?> worker) {
        return now(randomJobId(), worker);
    }

    public String now(String jobId, $.Function<ProgressGauge, ?> worker) {
        Job job = wrap(jobId, worker);
        executor().submit(job);
        return job.id();
    }

    /**
     * Prepare a job from worker. This function will return
     * a job ID and can be used to feed into the {@link #now(String)}
     * call
     *
     * @param worker the worker
     * @return the job ID allocated
     */
    public String prepare($.Function<ProgressGauge, ?> worker) {
        Job job = wrap(worker);
        return job.id();
    }

    /**
     * Run a job by ID now
     * @param jobId the job ID
     * @see #prepare(Osgl.Function)
     */
    public void now(String jobId) {
        Job job = $.notNull(jobById(jobId));
        executor().submit(job);
    }

    public <T> Future<T> delay(Callable<T> callable, long delay, TimeUnit timeUnit) {
        return executor().schedule(callable, delay, timeUnit);
    }

    public void delay(Runnable runnable, long delay, TimeUnit timeUnit) {
        executor().schedule(wrap(runnable), delay, timeUnit);
    }

    public <T> Future<T> delay(Callable<T> callable, String delay) {
        int seconds = parseTime(delay);
        return executor().schedule(callable, seconds, TimeUnit.SECONDS);
    }

    public void delay(Runnable runnable, String delay) {
        int seconds = parseTime(delay);
        executor().schedule(wrap(runnable), seconds, TimeUnit.SECONDS);
    }

    public void every(String id, Runnable runnable, String interval) {
        JobTrigger.every(interval).schedule(this, Job.multipleTimes(id, runnable, this));
    }

    public void every(Runnable runnable, String interval) {
        JobTrigger.every(interval).schedule(this, Job.multipleTimes(runnable, this));
    }

    public void every(Runnable runnable, long interval, TimeUnit timeUnit) {
        JobTrigger.every(interval, timeUnit).schedule(this, Job.multipleTimes(runnable, this));
    }

    public void every(String id, Runnable runnable, long interval, TimeUnit timeUnit) {
        JobTrigger.every(interval, timeUnit).schedule(this, Job.multipleTimes(id, runnable, this));
    }

    public void fixedDelay(Runnable runnable, String interval) {
        JobTrigger.every(interval).schedule(this, Job.multipleTimes(runnable, this));
    }

    public void fixedDelay(String id, Runnable runnable, String interval) {
        JobTrigger.every(interval).schedule(this, Job.multipleTimes(id, runnable, this));
    }

    public void fixedDelay(Runnable runnable, long interval, TimeUnit timeUnit) {
        JobTrigger.fixedDelay(interval, timeUnit).schedule(this, Job.multipleTimes(runnable, this));
    }

    public void fixedDelay(String id, Runnable runnable, long interval, TimeUnit timeUnit) {
        JobTrigger.fixedDelay(interval, timeUnit).schedule(this, Job.multipleTimes(id, runnable, this));
    }

    private int parseTime(String timeDuration) {
        if (timeDuration.startsWith("${") && timeDuration.endsWith("}")) {
            timeDuration = app().config().get(timeDuration.substring(2, timeDuration.length() - 1));
        }
        return Time.parseDuration(timeDuration);
    }

    public void on(DateTime instant, Runnable runnable) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("schedule runnable[%s] on %s", runnable, instant);
        }
        DateTime now = DateTime.now();
        E.illegalArgumentIf(instant.isBefore(now));
        Seconds seconds = Seconds.secondsBetween(now, instant);
        executor().schedule(wrap(runnable), seconds.getSeconds(), TimeUnit.SECONDS);
    }

    public <T> Future<T> on(DateTime instant, Callable<T> callable) {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("schedule callable[%s] on %s", callable, instant);
        }
        DateTime now = DateTime.now();
        E.illegalArgumentIf(instant.isBefore(now));
        Seconds seconds = Seconds.secondsBetween(now, instant);
        return executor().schedule(callable, seconds.getSeconds(), TimeUnit.SECONDS);
    }

    public void on(AppEventId appEvent, final Runnable runnable) {
        on(appEvent, runnable, false);
    }

    public void on(AppEventId appEvent, final Runnable runnable, boolean runImmediatelyIfEventDispatched) {
        on(appEvent, runnable.toString(), runnable, runImmediatelyIfEventDispatched);
    }

    public void post(AppEventId appEvent, final Runnable runnable) {
        post(appEvent, runnable, false);
    }

    public void post(AppEventId appEvent, final Runnable runnable, boolean runImmediatelyIfEventDispatched) {
        Job job = jobById(appEventJobId(appEvent));
        if (null == job) {
            processDelayedJob(wrap(runnable), runImmediatelyIfEventDispatched);
        } else {
            job.addFollowingJob(Job.once(runnable, this));
        }
    }

    public void on(AppEventId appEvent, String jobId, final Runnable runnable) {
        on(appEvent, jobId, runnable, false);
    }

    public void on(AppEventId appEvent, String jobId, final Runnable runnable, boolean runImmediatelyIfEventDispatched) {
        boolean traceEnabled = LOGGER.isTraceEnabled();
        if (traceEnabled) {
            LOGGER.trace("binding job[%s] to app event: %s, run immediately if event dispatched: %s", jobId, appEvent, runImmediatelyIfEventDispatched);
        }
        Job job = jobById(appEventJobId(appEvent));
        if (null == job) {
            if (traceEnabled) {
                LOGGER.trace("process delayed job: %s", jobId);
            }
            processDelayedJob(wrap(runnable), runImmediatelyIfEventDispatched);
        } else {
            if (traceEnabled) {
                LOGGER.trace("schedule job: %s", jobId);
            }
            job.addPrecedenceJob(Job.once(jobId, runnable, this));
        }
    }

    public void post(AppEventId appEvent, String jobId, final Runnable runnable) {
        post(appEvent, jobId, runnable, false);
    }

    public void post(AppEventId appEvent, String jobId, final Runnable runnable, boolean runImmediatelyIfEventDispatched) {
        Job job = jobById(appEventJobId(appEvent));
        if (null == job) {
            processDelayedJob(wrap(runnable), runImmediatelyIfEventDispatched);
        } else {
            job.addFollowingJob(Job.once(jobId, runnable, this));
        }
    }

    public void alongWith(AppEventId appEvent, String jobId, final Runnable runnable) {
        Job job = jobById(appEventJobId(appEvent));
        if (null == job) {
            processDelayedJob(wrap(runnable), false);
        } else {
            job.addParallelJob(Job.once(jobId, runnable, this));
        }
    }

    @Override
    protected void warn(String format, Object... args) {
        super.warn(format, args);
    }

    private void processDelayedJob(final Job job, boolean runImmediatelyIfEventDispatched) {
        if (runImmediatelyIfEventDispatched) {
            try {
                job.run();
            } catch (Exception e) {
                Act.LOGGER.error(e, "Error running job");
            }
        } else {
            now(job);
        }
    }

    /**
     * Cancel a scheduled Job by ID
     * @param jobId the job Id
     */
    public void cancel(String jobId) {
        Job job = jobById(jobId);
        if (null != job) {
            removeJob(job);
        } else {
            ScheduledFuture future = scheduled.remove(jobId);
            if (null != future) {
                future.cancel(true);
            }
        }
    }

    public void beforeAppStart(final Runnable runnable) {
        on(AppEventId.START, runnable);
    }

    public void afterAppStart(final Runnable runnable) {
        post(AppEventId.START, runnable);
    }

    public void beforeAppStop(final Runnable runnable) {
        on(AppEventId.STOP, runnable);
    }

    public SimpleProgressGauge progressGauge(String jobId) {
        return jobById(jobId).progress();
    }

    public void setJobProgressGauge(String jobId, ProgressGauge progressGauge) {
        Job job = jobById(jobId);
        if (null == job) {
            LOGGER.warn("cannot find job by Id: " + jobId);
        } else {
            job.setProgressGauge(progressGauge);
        }
    }

    C.List<Job> jobs() {
        return C.list(jobs.values());
    }

    C.List<Job> virtualJobs() {
        final AppJobManager jobManager = Act.jobManager();
        return C.list(scheduled.entrySet()).map(new $.Transformer<Map.Entry<String, ScheduledFuture>, Job>() {
            @Override
            public Job transform(Map.Entry<String, ScheduledFuture> entry) {
                return new Job(entry.getKey(), jobManager);
            }
        });
    }

    void futureScheduled(String id, ScheduledFuture future) {
        scheduled.putIfAbsent(id, future);
    }

    Job jobById(String id) {
        Job job = jobs.get(id);
        if (null == job) {
            ScheduledFuture future = scheduled.get(id);
            if (null != future) {
                return new Job(id, Act.jobManager());
            }
            Act.LOGGER.warn("cannot find job by id: %s", id);
        }
        return job;
    }

    void addJob(Job job) {
        String id = job.id();
        E.illegalStateIf(jobs.containsKey(id), "job already registered: %s", id);
        jobs.put(id, job);
    }

    void removeJob(Job job) {
        String id = job.id();
        jobs.remove(id);
        ScheduledFuture future = scheduled.remove(id);
        if (null != future) {
            future.cancel(true);
        }
    }

    ScheduledThreadPoolExecutor executor() {
        return executor;
    }

    private void initExecutor(App app) {
        int poolSize = app.config().jobPoolSize();
        executor = new ScheduledThreadPoolExecutor(poolSize, new AppThreadFactory("jobs"), new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("init executor with thread pool: %s", poolSize);
        }
    }

    private void createAppEventListener(AppEventId appEventId) {
        String jobId = appEventJobId(appEventId);
        Job job = new Job(jobId, this);
        app().eventBus().bind(appEventId, new _AppEventListener(jobId, job));
    }

    private static class _AppEventListener extends AppEventListenerBase {
        private Runnable worker;
        _AppEventListener(String id, Runnable worker) {
            super(id);
            this.worker = $.NPE(worker);
        }

        @Override
        public void on(EventObject event) throws Exception {
            worker.run();
        }

        @Override
        protected void releaseResources() {
            if (null != worker && worker instanceof Destroyable) {
                ((Destroyable) worker).destroy();
            }
        }
    }

    private Job wrap(Runnable runnable) {
        return new ContextualJob(randomJobId(), runnable);
    }

    private Job wrap(String name, Runnable runnable) {
        return new ContextualJob(name, runnable);
    }

    private Job wrap(Callable callable) {
        return new ContextualJob(randomJobId(), callable);
    }

    private Job wrap(String name, Callable callable) {
        return new ContextualJob(name, callable);
    }

    private Job wrap($.Function<ProgressGauge, ?> worker) {
        return new ContextualJob(randomJobId(), worker);
    }

    private Job wrap(String name, $.Function<ProgressGauge, ?> worker) {
        return new ContextualJob(name, worker);
    }

    private class ContextualJob extends Job {

        private JobContext origin_ = JobContext.copy();

        ContextualJob(String id, final Callable<?> callable) {
            super(id, AppJobManager.this, callable);
        }

        ContextualJob(String id, final Runnable runnable) {
            super(id, AppJobManager.this, new $.F0() {
                @Override
                public Object apply() throws NotAppliedException, $.Break {
                    runnable.run();
                    return null;
                }
            }, true);
            foo();
        }

        ContextualJob(String id, final $.Function<ProgressGauge, ?> worker) {
            super(id, AppJobManager.this, worker);
            foo();
        }

        @Override
        protected void _before() {
            // copy the JobContext of parent thread into the current thread
            JobContext.init(origin_);
        }

        @Override
        protected void _finally() {
            JobContext.clear();
            removeJob(this);
        }

        private void foo() {
            app().eventBus().once(MailerContext.InitEvent.class, new OnceEventListenerBase<MailerContext.InitEvent>() {
                @Override
                public boolean tryHandle(MailerContext.InitEvent event) throws Exception {
                    MailerContext mailerContext = MailerContext.current();
                    if (null != mailerContext) {
                        _before();
                        return true;
                    }
                    return true;
                }
            });
        }
    }

    private String randomJobId() {
        return app().cuid() + S.random(4);
    }

}
