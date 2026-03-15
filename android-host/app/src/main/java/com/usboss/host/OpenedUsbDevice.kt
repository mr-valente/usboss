package com.usboss.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface OpenedUsbDevice {
    val displayName: String
    val systemPath: String

    fun protocolSpec(): Protocol.OpenDeviceSpec

    fun startInputPump(
        scope: CoroutineScope,
        onReport: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ): Job

    fun sendOutputReport(reportType: Int, reportId: Int, data: ByteArray): Int

    fun getReport(reportType: Int, reportId: Int): ByteArray

    fun setReport(reportType: Int, reportId: Int, data: ByteArray): Int

    fun close()
}
