package dev.southstar21.droidtosh.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import dev.southstar21.droidtosh.protocol.v1.PhoneAccount

class PhoneAccountsProvider(private val context: Context) {
    private val telecomManager: TelecomManager = context.getSystemService(TelecomManager::class.java)

    fun accounts(): List<PhoneAccount> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return emptyList()
        return runCatching {
            telecomManager.callCapablePhoneAccounts.map { handle ->
                PhoneAccount.newBuilder()
                    .setId(handle.id)
                    .setLabel(telecomManager.getPhoneAccount(handle)?.label?.toString().orEmpty())
                    .build()
            }
        }.getOrDefault(emptyList())
    }

    fun labels(): Map<String, String> = accounts().associate { it.id to it.label }
}
