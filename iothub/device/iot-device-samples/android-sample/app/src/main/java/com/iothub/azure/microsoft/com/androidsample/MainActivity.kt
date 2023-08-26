package com.iothub.azure.microsoft.com.androidsample

import android.os.Bundle
import android.os.Process
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.microsoft.azure.sdk.iot.device.DeviceClient
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol
import com.microsoft.azure.sdk.iot.device.Message
import com.microsoft.azure.sdk.iot.device.exceptions.IotHubClientException
import com.microsoft.azure.sdk.iot.device.twin.*
import java.util.*

@Suppress("UNUSED_PARAMETER")
class MainActivity : AppCompatActivity() {
    private val connString = ""
    private var client: DeviceClient? = null
    private var twin: Twin? = null
    private val protocol = IotHubClientProtocol.MQTT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        try {
            initClient()
        } catch (e2: Exception) {
            logE("Exception while opening IoTHub connection")
            e2.printStackTrace()
        }
    }

    private fun initClient() = try {
        val client = DeviceClient(connString, protocol).apply { client = this }
        client.open(true)

        val callback = if (protocol == IotHubClientProtocol.MQTT) {
            MessageCallbackMqtt()
        } else {
            MessageCallbackImpl()
        }

        val methodCallback = AppMethodCallback()
        val desiredPropertiesCallback = AppDesiredPropertiesCallback(twin)

        client.setMessageCallback(callback, Counter(0))
        client.subscribeToMethods(methodCallback, null)
        client.subscribeToDesiredProperties(desiredPropertiesCallback, null)

        twin = client.twin
    } catch (e2: Exception) {
        logE("Exception while opening IoTHub connection: ${e2.message}")
        client?.close()
    }

    @Throws(IotHubClientException::class, InterruptedException::class)
    fun btnGetTwinOnClick(v: View?) {
        try {
            logD("Get device Twin...")
            twin = client!!.twin
            logD(twin.toString())
        } catch (e: Exception) {
            logE("Exception while getting twin: ${e.message}")
        }
    }

    @Throws(IotHubClientException::class, InterruptedException::class)
    fun btnUpdateReportedOnClick(v: View?) {
        try {
            val newTemperatureValue = Random().nextInt(80)
            logD("Updating reported properties to set HomeTemp(F) to new value $newTemperatureValue")
            twin!!.reportedProperties["HomeTemp(F)"] = newTemperatureValue
            val response = client!!.updateReportedProperties(twin!!.reportedProperties)
            twin!!.reportedProperties.version = response.version
            logD("Reported properties update sent successfully. New version is " + response.version)
        } catch (e: Exception) {
            logE("Exception while updating report: ${e.message}")
        }
    }

    fun btnSendOnClick(v: View?) {
        val temperature = 20.0 + Math.random() * 10
        val humidity = 30.0 + Math.random() * 20
        val msgStr = "{\"temperature\":$temperature,\"humidity\":$humidity}"
        try {
            val msg = Message(msgStr)
            msg.setProperty("temperatureAlert", if (temperature > 28) "true" else "false")
            msg.messageId = UUID.randomUUID().toString()
            logD(msgStr)
            client!!.sendEventAsync(msg, MessageSentCallbackImpl(), null)
        } catch (e: Exception) {
            logE("Exception while sending event: ${e.message}")
        }
    }

    private fun stopClient() {
        val operatingSystem = System.getProperty("os.name")
        client?.close()
        println("Shutting down...$operatingSystem")
        Process.killProcess(Process.myPid())
    }

    fun btnStopOnClick(v: View?) {
        stopClient()
    }
}