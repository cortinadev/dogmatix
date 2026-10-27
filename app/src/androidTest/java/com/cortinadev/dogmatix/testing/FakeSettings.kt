package com.cortinadev.dogmatix.testing

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.lang.reflect.Proxy

/**
 * A [SettingsRepository] for services that read a couple of settings: each Flow property named in
 * [values] (`"concurrentDownloads" to flowOf(1)`) answers with that flow, any other Flow property
 * with an empty one, and anything else throws. A dynamic proxy, so settings added to the
 * interface later never break the tests.
 */
fun fakeSettings(vararg values: Pair<String, Flow<*>>): SettingsRepository {
    val flows = values.toMap()
    return Proxy.newProxyInstance(
        SettingsRepository::class.java.classLoader,
        arrayOf(SettingsRepository::class.java)
    ) { proxy, method, args ->
        when {
            method.name == "toString" && method.parameterCount == 0 -> "FakeSettings(${flows.keys})"
            method.name == "hashCode" && method.parameterCount == 0 -> System.identityHashCode(proxy)
            method.name == "equals" && method.parameterCount == 1 -> proxy === args?.get(0)
            method.parameterCount == 0 && method.name.startsWith("get") && Flow::class.java.isAssignableFrom(method.returnType) ->
                flows[method.name.removePrefix("get").replaceFirstChar { it.lowercase() }] ?: emptyFlow<Any>()
            else -> throw UnsupportedOperationException("FakeSettings does not answer ${method.name}")
        }
    } as SettingsRepository
}
