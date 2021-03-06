/*
 * Copyright 2012-2015 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.money.concurrent

import java.util.concurrent._

import com.comcast.money.logging.TraceLogging
import org.slf4j.MDC

import com.comcast.money.internal.{ MDCSupport, SpanLocal }

object TraceFriendlyThreadPoolExecutor {

  /**
   * Fixed size pool that inherits trace id from thread that submits it.
   *
   * @param nThreads max number of threads to allow
   * @return a bounded threadpool
   */
  def newFixedThreadPool(nThreads: Int): ExecutorService = {
    new TraceFriendlyThreadPoolExecutor(
      nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue[Runnable]
    )
  }

  /**
   * Unbounded pool that inherits trace id from thread that submits it.
   * @return a virtually unbounded threadpool
   */
  def newCachedThreadPool: ExecutorService = {
    new TraceFriendlyThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue[Runnable])
  }
}

/**
 * This class must be used as the Executor when supporting Tracing in an application.  Ensures that the
 * trace context is propagated from the calling thread to the worker thread in the pool
 */
class TraceFriendlyThreadPoolExecutor(corePoolSize: Int, maximumPoolSize: Int, keepAliveTime: Long, unit: TimeUnit,
  workQueue: BlockingQueue[Runnable])
    extends ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue)
    with TraceLogging {

  lazy val mdcSupport = new MDCSupport()

  def this(corePoolSize: Int, maximumPoolSize: Int, keepAliveTime: Long, unit: TimeUnit,
    workQueue: BlockingQueue[Runnable], threadFactory: ThreadFactory,
    rejectedExecutionHandler: RejectedExecutionHandler) = {
    this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue)
    setThreadFactory(threadFactory)
    setRejectedExecutionHandler(rejectedExecutionHandler)
  }

  override def execute(command: Runnable) = {
    val inheritedTraceId = SpanLocal.current
    val submittingThreadsContext = MDC.getCopyOfContextMap

    super.execute(
      new Runnable {
        override def run = {
          mdcSupport.propogateMDC(Option(submittingThreadsContext))
          SpanLocal.clear()
          inheritedTraceId.map(SpanLocal.push)
          try {
            command.run()
          } catch {
            case t: Throwable =>
              logException(t)
              throw t
          } finally {
            SpanLocal.clear()
            MDC.clear()
          }
        }
      }
    )
  }
}
