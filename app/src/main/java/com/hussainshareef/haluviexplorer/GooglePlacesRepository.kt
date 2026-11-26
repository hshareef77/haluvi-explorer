package com.hussainshareef.haluviexplorer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the user-entered Google key with a key held by Android Keystore. */
object GooglePlacesKeyStore {
    private const val PREFS = "google_places_secure"
    private const val VALUE = "encrypted_key"
    private const val KEY_ALIAS = "haluvi_explorer_google_places_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    fun load(context: Context): String? = runCatching {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, null) ?: return null
        val parts = encoded.split(":", limit = 2)
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun save(context: Context, apiKey: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(VALUE, value).commit()
    }.getOrDefault(false)

    fun delete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(VALUE).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

data class GooglePlaceResult(val id: String, val name: String, val category: String, val address: String)

object GooglePlacesRepository {
    private class KeyChangedException : Exception()
    private var initializedFingerprint: String? = null
    private var client: com.google.android.libraries.places.api.net.PlacesClient? = null

    fun search(
        context: Context,
        apiKey: String,
        center: GeoPoint,
        radiusMeters: Double,
        onComplete: (List<GooglePlaceResult>?, Int?) -> Unit
    ) {
        val placesClient = runCatching {
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(apiKey.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            synchronized(this) {
                if (client == null) {
                    com.google.android.libraries.places.api.Places.initializeWithNewPlacesApiEnabled(context.applicationContext, apiKey)
                    client = com.google.android.libraries.places.api.Places.createClient(context.applicationContext)
                    initializedFingerprint = fingerprint
                }
                if (initializedFingerprint != fingerprint) throw KeyChangedException()
                client!!
            }
        }.getOrElse {
            onComplete(null, if (it is KeyChangedException) -2 else null)
            return
        }

        val circle = com.google.android.libraries.places.api.model.CircularBounds.newInstance(
            com.google.android.gms.maps.model.LatLng(center.lat, center.lon),
            radiusMeters.coerceIn(1.0, 50_000.0)
        )
        val fields = listOf(
            com.google.android.libraries.places.api.model.Place.Field.ID,
            com.google.android.libraries.places.api.model.Place.Field.DISPLAY_NAME,
            com.google.android.libraries.places.api.model.Place.Field.ADDRESS,
            com.google.android.libraries.places.api.model.Place.Field.TYPES
        )
        val request = com.google.android.libraries.places.api.net.SearchNearbyRequest.builder(circle, fields)
            .setMaxResultCount(20)
            .setRankPreference(com.google.android.libraries.places.api.net.SearchNearbyRequest.RankPreference.DISTANCE)
            .build()
        placesClient.searchNearby(request)
            .addOnSuccessListener { response ->
                val results = response.places.map { place ->
                    GooglePlaceResult(
                        id = place.id.orEmpty(),
                        name = place.displayName ?: "Unnamed Google place",
                        category = place.types?.firstOrNull()?.replace('_', ' ') ?: "place",
                        address = place.address.orEmpty()
                    )
                }
                onComplete(results, null)
            }
            .addOnFailureListener { error ->
                val code = (error as? com.google.android.gms.common.api.ApiException)?.statusCode
                onComplete(null, code)
            }
    }
}
