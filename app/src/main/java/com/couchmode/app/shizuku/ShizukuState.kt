package com.couchmode.app.shizuku

sealed class ShizukuState {
    data object Unavailable : ShizukuState()
    data object Available : ShizukuState()
    data object PermissionDenied : ShizukuState()
    data class Connected(val remotePid: Int) : ShizukuState()
}
