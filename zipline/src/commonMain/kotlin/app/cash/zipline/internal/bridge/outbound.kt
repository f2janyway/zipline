/*
 * Copyright (C) 2021 Square, Inc.
 *
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
 */
package app.cash.zipline.internal.bridge

import app.cash.zipline.ZiplineService
import app.cash.zipline.internal.decodeFromStringFast
import app.cash.zipline.internal.encodeToStringFast
import app.cash.zipline.internal.ziplineInternalPrefix
import kotlin.coroutines.Continuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json

/**
 * Generated code extends this base class to make calls into an application-layer interface that is
 * implemented by another platform in the same process.
 */
@PublishedApi
internal interface OutboundBridge {
  class Context(
    private val instanceName: String,
    val json: Json,
    @PublishedApi internal val endpoint: Endpoint,
    private val ziplineFunctions: List<ZiplineFunction<*>>,
  ) {
    val serializersModule = json.serializersModule
    var closed = false

    fun call(
      service: ZiplineService,
      functionIndex: Int,
      vararg args: Any?,
    ): Any? {
      val function = ziplineFunctions[functionIndex]
      val argsList = args.toList()
      val call = OutboundCall2(instanceName, function, null, argsList)
      val encodedCall = json.encodeToStringFast(InternalCallSerializer(endpoint), call)

      val callStartResult = endpoint.eventListener.callStart(instanceName, service, function.name, argsList)
      val encodedResult = endpoint.outboundChannel.call(arrayOf(encodedCall))

      val result = json.decodeFromStringFast(function.callResultSerializer, encodedResult.single())
      endpoint.eventListener.callEnd(instanceName, service, function.name, argsList, result, callStartResult)
      return result.getOrThrow()
    }

    suspend fun callSuspending(
      service: ZiplineService,
      functionIndex: Int,
      vararg args: Any?,
    ): Any? {
      val function = ziplineFunctions[functionIndex]
      val argsList = args.toList()
      val callbackName = endpoint.generateName(prefix = ziplineInternalPrefix)
      val call = OutboundCall2(instanceName, function, callbackName, argsList)
      val encodedCall = json.encodeToStringFast(InternalCallSerializer(endpoint), call)
      val callStartResult = endpoint.eventListener.callStart(instanceName, service, function.name, argsList)
      return suspendCancellableCoroutine { continuation ->
        endpoint.incompleteContinuations += continuation
        endpoint.scope.launch {
          val suspendCallback = RealSuspendCallback(
            call = call,
            service = service,
            continuation = continuation,
            callStartResult = callStartResult,
          )
          endpoint.bind<SuspendCallback>(callbackName, suspendCallback)

          continuation.invokeOnCancellation {
            if (suspendCallback.completed) return@invokeOnCancellation
            val cancelCallbackName = endpoint.cancelCallbackName(callbackName)
            val cancelCallback = endpoint.take<CancelCallback>(cancelCallbackName)
            cancelCallback.cancel()
          }

          endpoint.outboundChannel.call(arrayOf(encodedCall))
        }
      }
    }

    private inner class RealSuspendCallback<R>(
      val call: OutboundCall2,
      val service: ZiplineService,
      val continuation: Continuation<R>,
      val callStartResult: Any?,
    ) : SuspendCallback {
      /** True once this has been called. Used to prevent cancel-after-complete. */
      var completed = false

      override fun call(response: String) {
        completed = true
        // Suspend callbacks are one-shot. When triggered, remove them immediately.
        endpoint.remove(call.callbackName!!)
        val result = json.decodeFromStringFast(call.function.callResultSerializer as CallResultSerializer<R>, response)
        endpoint.incompleteContinuations -= continuation
        endpoint.eventListener.callEnd(
          name = instanceName,
          service = service,
          functionName = call.function.name,
          args = call.args,
          result = result,
          callStartResult = callStartResult
        )
        continuation.resumeWith(result)
      }
    }
  }
}
