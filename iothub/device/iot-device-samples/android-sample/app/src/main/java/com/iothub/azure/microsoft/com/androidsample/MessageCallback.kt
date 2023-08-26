package com.iothub.azure.microsoft.com.androidsample

import com.microsoft.azure.sdk.iot.device.IotHubMessageResult
import com.microsoft.azure.sdk.iot.device.Message
import com.microsoft.azure.sdk.iot.device.MessageCallback
import com.microsoft.azure.sdk.iot.device.MessageSentCallback
import com.microsoft.azure.sdk.iot.device.exceptions.IotHubClientException
import com.microsoft.azure.sdk.iot.device.twin.*

class AppMethodCallback : MethodCallback {
    override fun onMethodInvoked(
            methodName: String?,
            directMethodPayload: DirectMethodPayload?,
            payload: Any?
    ): DirectMethodResponse {
        // simulating a device that knows what to do when given a command with the method name "performAction".
        return if (methodName == "performAction") {
            DirectMethodResponse(METHOD_SUCCESS, null)
        } else {
            // if the command was unrecognized, return a status code to signal that to the client
            // that invoked the method.
            DirectMethodResponse(METHOD_NOT_DEFINED, null)
        }
    }

    companion object {
        private const val METHOD_SUCCESS = 200
        private const val METHOD_NOT_DEFINED = 404
    }
}

class AppDesiredPropertiesCallback(
        private val twin: Twin?,
) : DesiredPropertiesCallback {
    override fun onDesiredPropertiesUpdated(desiredPropertiesUpdate: Twin, context: Any?) {
        try {
            logD("Received desired property update:")
            logD(desiredPropertiesUpdate.toString())
            twin!!.desiredProperties.putAll(desiredPropertiesUpdate.desiredProperties)
            twin.desiredProperties.version = desiredPropertiesUpdate.desiredProperties.version
        } catch (e: Exception) {
            logE("Exception onDesiredPropertiesUpdated: ${e.message}")
        }
    }
}

class MessageCallbackImpl : MessageCallback {
    override fun onCloudToDeviceMessageReceived(
            msg: Message,
            context: Any
    ): IotHubMessageResult {
        val counter = context as Counter
        logD(
                "Received message $counter with content: " +
                        String(msg.bytes, Message.DEFAULT_IOTHUB_MESSAGE_CHARSET)
        )
        counter.increment()
        return IotHubMessageResult.COMPLETE
    }
}

// Our MQTT doesn't support abandon/reject, so we will only display the messaged received
// from IoTHub and return COMPLETE
class MessageCallbackMqtt : MessageCallback {
    override fun onCloudToDeviceMessageReceived(
            msg: Message,
            context: Any
    ): IotHubMessageResult {
        val counter = context as Counter
        logD(
                "Received message $counter with content: " +
                        String(msg.bytes, Message.DEFAULT_IOTHUB_MESSAGE_CHARSET)
        )
        counter.increment()
        return IotHubMessageResult.COMPLETE
    }
}

class MessageSentCallbackImpl : MessageSentCallback {
    override fun onMessageSent(message: Message, e: IotHubClientException?, o: Any) {
        if (e == null) {
            logD("IoT Hub responded to message ${message.messageId} with status OK")
        } else {
            logD("IoT Hub responded to message ${message.messageId} with status ${e.statusCode.name}")
        }
    }
}