package com.iothub.azure.microsoft.com.androidsample

/**
 * Used as a counter in the message callback.
 */
class Counter(private var num: Int) {
    fun get(): Int = num

    fun increment() {
        num++
    }

    override fun toString(): String = num.toString()
}