package com.noyclic.questphonexr.runtime
import com.noyclic.questphonexr.catalog.ApkLaunchRoute
import org.junit.Assert.*
import org.junit.Test
class OpenXrRuntimeDiscoveryTest {
 @Test fun mapsAndroidAbisToKhronosIdentifiers(){
  assertEquals("aarch64",OpenXrRuntimeDiscovery.toLoaderAbi("arm64-v8a"))
  assertEquals("armv7a-vfp",OpenXrRuntimeDiscovery.toLoaderAbi("armeabi-v7a"))
  assertEquals("x86_64",OpenXrRuntimeDiscovery.toLoaderAbi("x86_64"))
  assertEquals("i686",OpenXrRuntimeDiscovery.toLoaderAbi("x86"))
  assertNull(OpenXrRuntimeDiscovery.toLoaderAbi("mips"))
 }
 @Test fun choosesAbiMatchingProcessBitness(){
  assertEquals("aarch64",OpenXrRuntimeDiscovery.currentProcessLoaderAbi(listOf("arm64-v8a","armeabi-v7a"),true))
  assertEquals("armv7a-vfp",OpenXrRuntimeDiscovery.currentProcessLoaderAbi(listOf("arm64-v8a","armeabi-v7a"),false))
 }
 @Test fun installedOpenXrAppNeedsDiscoveredRuntime(){
  val found=OpenXrRuntimeDiscoveryReport(OpenXrRuntimeDiscoveryState.ACTIVE_RUNTIME_FOUND,"found","aarch64",emptyList())
  val ready=OpenXrLaunchCoordinator.evaluate(ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE,found,true)
  assertEquals(OpenXrLaunchReadinessState.READY_TO_HAND_OFF,ready.state)
  assertTrue(ready.canHandOffToAndroid)
  val absent=OpenXrRuntimeDiscoveryReport(OpenXrRuntimeDiscoveryState.NO_ACTIVE_RUNTIME,"none","aarch64",emptyList())
  val blocked=OpenXrLaunchCoordinator.evaluate(ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE,absent,true)
  assertEquals(OpenXrLaunchReadinessState.ACTIVE_RUNTIME_REQUIRED,blocked.state)
  assertFalse(blocked.canHandOffToAndroid)
 }
 @Test fun metaRuntimeDoesNotPassGenericOpenXrGate(){
  val absent=OpenXrRuntimeDiscoveryReport(OpenXrRuntimeDiscoveryState.BROKER_UNAVAILABLE,"not found","aarch64",emptyList())
  val result=OpenXrLaunchCoordinator.evaluate(ApkLaunchRoute.QUEST_RUNTIME_REQUIRED,absent,true)
  assertEquals(OpenXrLaunchReadinessState.META_RUNTIME_REQUIRED,result.state)
  assertFalse(result.canHandOffToAndroid)
 }
}
