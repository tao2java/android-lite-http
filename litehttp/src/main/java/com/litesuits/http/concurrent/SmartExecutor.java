package com.litesuits.http.concurrent;

import android.util.Log;
import com.litesuits.http.log.HttpLog;
import com.litesuits.http.utils.HttpUtil;

import java.util.LinkedList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A smart thread pool executor, about {@link SmartExecutor}:
 *
 * <ul>
 * <li>keep {@link #coreSize} tasks concurrent, and put them in {@link #runningList},
 * maximum number of running-tasks at the same time is {@link #coreSize}.</li>
 * <li>when {@link #runningList} is full, put new task in {@link #waitingList} waiting for execution,
 * maximum of waiting-tasks number is {@link #queueSize}.</li>
 * <li>when {@link #waitingList} is full, new task is performed by {@link OverloadPolicy}.</li>
 * <li>when running task is completed, take it out from {@link #runningList}.</li>
 * <li>schedule next by {@link SchedulePolicy}, take next task out from {@link #waitingList} to execute,
 * and so on until {@link #waitingList} is empty.</li>
 *
 * </ul>
 *
 * @author MaTianyu
 * @date 2015-04-23
 */
public class SmartExecutor implements Executor {
    private static final String TAG = SmartExecutor.class.getSimpleName();
    private static final int CPU_CORE = HttpUtil.getCoresNumbers();
    private static final int DEFAULT_CACHE_SENCOND = 5;
    private static ThreadPoolExecutor threadPool;
    private int coreSize = CPU_CORE;
    private int queueSize = coreSize * 32;
    private final Object lock = new Object();
    private LinkedList<WrappedRunnable> runningList = new LinkedList<WrappedRunnable>();
    private LinkedList<WrappedRunnable> waitingList = new LinkedList<WrappedRunnable>();
    private SchedulePolicy schedulePolicy = SchedulePolicy.FirstInFistRun;
    private OverloadPolicy overloadPolicy = OverloadPolicy.DiscardOldTaskInQueue;


    public SmartExecutor() {
        initThreadPool();
    }

    public SmartExecutor(int coreSize, int queueSize) {
        this.coreSize = coreSize;
        this.queueSize = queueSize;
        initThreadPool();
    }

    protected synchronized void initThreadPool() {
        if (HttpLog.isPrint) {
            HttpLog.v(TAG, "SmartExecutor core-queue size: " + coreSize + " - " + queueSize
                           + "  running-wait task: " + runningList.size() + " - " + waitingList.size());
        }
        if (threadPool == null) {
            threadPool = createDefaultThreadPool();
        }
    }

    public static ThreadPoolExecutor createDefaultThreadPool() {
        return new ThreadPoolExecutor(
                CPU_CORE,
                Integer.MAX_VALUE,
                DEFAULT_CACHE_SENCOND, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(),
                new ThreadFactory() {
                    static final String NAME = "lite-";
                    AtomicInteger IDS = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, NAME + IDS.getAndIncrement());
                    }
                },
                new ThreadPoolExecutor.DiscardPolicy());
    }

    public static void setThreadPool(ThreadPoolExecutor threadPool) {
        SmartExecutor.threadPool = threadPool;
    }

    public static ThreadPoolExecutor getThreadPool() {
        return threadPool;
    }

    public boolean cancelWaitingTask(Runnable command) {
        boolean removed = false;
        synchronized (lock) {
            int size = waitingList.size();
            if (size > 0) {
                for (int i = size - 1; i >= 0; i--) {
                    if (waitingList.get(i).getRealRunnable() == command) {
                        waitingList.remove(i);
                        removed = true;
                    }
                }
            }
        }
        return removed;
    }

    interface WrappedRunnable extends Runnable {
        Runnable getRealRunnable();
    }

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTask<T>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<T>(callable);
    }

    /**
     * submit runnable
     */
    public Future<?> submit(Runnable task) {
        RunnableFuture<Void> ftask = newTaskFor(task, null);
        execute(ftask);
        return ftask;
    }

    /**
     * submit runnable
     */
    public <T> Future<T> submit(Runnable task, T result) {
        RunnableFuture<T> ftask = newTaskFor(task, result);
        execute(ftask);
        return ftask;
    }

    /**
     * submit callable
     */
    public <T> Future<T> submit(Callable<T> task) {
        RunnableFuture<T> ftask = newTaskFor(task);
        execute(ftask);
        return ftask;
    }


    /**
     * submit RunnableFuture task
     */
    public <T> void submit(RunnableFuture<T> task) {
        execute(task);
    }


    /**
     * When {@link #execute(Runnable)} is called, {@link SmartExecutor} perform actions:
     * <ol>
     * <li>if fewer than {@link #coreSize} tasks are running, post new task in {@link #runningList} and execute it immediately.</li>
     * <li>if more than {@link #coreSize} tasks are running, and fewer than {@link #queueSize} tasks are waiting, put task in {@link #waitingList}.</li>
     * <li>if more than {@link #queueSize} tasks are waiting ,schedule new task by {@link OverloadPolicy}</li>
     * <li>if running task is completed, schedule next task by {@link SchedulePolicy} until {@link #waitingList} is empty.</li>
     * </ol>
     */
    @Override
    public void execute(final Runnable command) {
        if (command == null) {
            return;
        }

        WrappedRunnable scheduler = new WrappedRunnable() {
            @Override
            public Runnable getRealRunnable() {
                return command;
            }

            public Runnable realRunnable;

            @Override
            public void run() {
                try {
                    command.run();
                } finally {
                    scheduleNext(this);
                }
            }
        };

        boolean callerRun = false;
        synchronized (lock) {
            //if (HttpLog.isPrint) {
            //    HttpLog.v(TAG, "SmartExecutor core-queue size: " + coreSize + " - " + queueSize
            //                   + "  running-wait task: " + runningList.size() + " - " + waitingList.size());
            //}
            if (runningList.size() < coreSize) {
                runningList.add(scheduler);
                threadPool.execute(scheduler);
                //HttpLog.v(TAG, "SmartExecutor task execute");
            } else if (waitingList.size() < queueSize) {
                waitingList.addLast(scheduler);
                //HttpLog.v(TAG, "SmartExecutor task waiting");
            } else {
                //if (HttpLog.isPrint) {
                //    HttpLog.w(TAG, "SmartExecutor overload , policy is: " + overloadPolicy);
                //}
                switch (overloadPolicy) {
                    case DiscardNewTaskInQueue:
                        waitingList.pollLast();
                        waitingList.addLast(scheduler);
                        break;
                    case DiscardOldTaskInQueue:
                        waitingList.pollFirst();
                        waitingList.addLast(scheduler);
                        break;
                    case CallerRuns:
                        callerRun = true;
                        break;
                    case DiscardCurrentTask:
                        break;
                    case ThrowExecption:
                        throw new RuntimeException("Task rejected from lite smart executor. " + command.toString());
                    default:
                        break;
                }
            }
            //printThreadPoolInfo();
        }
        if (callerRun) {
            if (HttpLog.isPrint) {
                HttpLog.i(TAG, "SmartExecutor task running in caller thread");
            }
            command.run();
        }
    }

    private void scheduleNext(WrappedRunnable scheduler) {
        synchronized (lock) {
            boolean suc = runningList.remove(scheduler);
            //if (HttpLog.isPrint) {
            //    HttpLog.v(TAG, "Thread " + Thread.currentThread().getName()
            //                   + " is completed. remove prior: " + suc + ", try schedule next..");
            //}
            if (!suc) {
                runningList.clear();
                HttpLog.e(TAG,
                        "SmartExecutor scheduler remove failed, so clear all(running list) to avoid unpreditable error : " + scheduler);
            }
            if (waitingList.size() > 0) {
                WrappedRunnable waitingRun;
                switch (schedulePolicy) {
                    case LastInFirstRun:
                        waitingRun = waitingList.pollLast();
                        break;
                    case FirstInFistRun:
                        waitingRun = waitingList.pollFirst();
                        break;
                    default:
                        waitingRun = waitingList.pollLast();
                        break;
                }
                if (waitingRun != null) {
                    runningList.add(waitingRun);
                    threadPool.execute(waitingRun);
                    HttpLog.v(TAG, "Thread " + Thread.currentThread().getName() + " execute next task..");
                } else {
                    HttpLog.e(TAG,
                            "SmartExecutor get a NULL task from waiting queue: " + Thread.currentThread().getName());
                }
            } else {
                if (HttpLog.isPrint) {
                    HttpLog.v(TAG, "SmartExecutor: all tasks is completed. current thread: " +
                                   Thread.currentThread().getName());
                    //printThreadPoolInfo();
                }
            }
        }
    }

    public void printThreadPoolInfo() {
        if (HttpLog.isPrint) {
            Log.i(TAG, "___________________________");
            Log.i(TAG, "state (shutdown - terminating - terminated): " + threadPool.isShutdown()
                       + " - " + threadPool.isTerminating() + " - " + threadPool.isTerminated());
            Log.i(TAG, "pool size (core - max): " + threadPool.getCorePoolSize()
                       + " - " + threadPool.getMaximumPoolSize());
            Log.i(TAG, "task (active - complete - total): " + threadPool.getActiveCount()
                       + " - " + threadPool.getCompletedTaskCount() + " - " + threadPool.getTaskCount());
            Log.i(TAG, "waitingList size : " + threadPool.getQueue().size() + " , " + threadPool.getQueue());
        }
    }

    public int getCoreSize() {
        return coreSize;
    }

    public int getRunningSize() {
        return runningList.size();
    }

    public int getWaitingSize() {
        return waitingList.size();
    }

    /**
     * Set maximum number of concurrent tasks at the same time.
     * Recommended core size is CPU count.
     *
     * @param coreSize number of concurrent tasks at the same time
     * @return this
     */
    public SmartExecutor setCoreSize(int coreSize) {
        if (coreSize <= 0) {
            throw new NullPointerException("coreSize can not <= 0 !");
        }
        this.coreSize = coreSize;
        if (HttpLog.isPrint) {
            HttpLog.v(TAG, "SmartExecutor core-queue size: " + coreSize + " - " + queueSize
                           + "  running-wait task: " + runningList.size() + " - " + waitingList.size());
        }
        return this;
    }

    public int getQueueSize() {
        return queueSize;
    }

    /**
     * Adjust maximum number of waiting queue size by yourself or based on phone performance.
     * For example: CPU count * 32;
     *
     * @param queueSize waiting queue size
     * @return this
     */
    public SmartExecutor setQueueSize(int queueSize) {
        if (queueSize < 0) {
            throw new NullPointerException("queueSize can not < 0 !");
        }

        this.queueSize = queueSize;
        if (HttpLog.isPrint) {
            HttpLog.v(TAG, "SmartExecutor core-queue size: " + coreSize + " - " + queueSize
                           + "  running-wait task: " + runningList.size() + " - " + waitingList.size());
        }
        return this;
    }


    public OverloadPolicy getOverloadPolicy() {
        return overloadPolicy;
    }

    public void setOverloadPolicy(OverloadPolicy overloadPolicy) {
        if (overloadPolicy == null) {
            throw new NullPointerException("OverloadPolicy can not be null !");
        }
        this.overloadPolicy = overloadPolicy;
    }

    public SchedulePolicy getSchedulePolicy() {
        return schedulePolicy;
    }

    public void setSchedulePolicy(SchedulePolicy schedulePolicy) {
        if (schedulePolicy == null) {
            throw new NullPointerException("SchedulePolicy can not be null !");
        }
        this.schedulePolicy = schedulePolicy;
    }

}
