package com.noyclic.questphonexr.runtime
import com.noyclic.questphonexr.catalog.ApkLaunchRoute
enum class OpenXrLaunchReadinessState { NOT_OPENXR, DEVICE_BLOCKED, META_RUNTIME_REQUIRED, INSTALL_REQUIRED, ACTIVE_RUNTIME_REQUIRED, RUNTIME_LIBRARY_UNAVAILABLE, READY_TO_HAND_OFF }
data class OpenXrLaunchReadiness(val state:OpenXrLaunchReadinessState,val canHandOffToAndroid:Boolean,val message:String)
/** READY_TO_HAND_OFF is not proof of successful xrCreateInstance or gameplay. */
object OpenXrLaunchCoordinator {
 fun evaluate(route:ApkLaunchRoute,runtime:OpenXrRuntimeDiscoveryReport,isInstalled:Boolean):OpenXrLaunchReadiness {
  fun blocked(s:OpenXrLaunchReadinessState,m:String)=OpenXrLaunchReadiness(s,false,m)
  if(route==ApkLaunchRoute.DEVICE_REQUIREMENTS_BLOCKED)return blocked(OpenXrLaunchReadinessState.DEVICE_BLOCKED,"APK не проходит базовые требования Android/ABI/устройства.")
  if(route==ApkLaunchRoute.QUEST_RUNTIME_REQUIRED)return blocked(OpenXrLaunchReadinessState.META_RUNTIME_REQUIRED,"Meta/Quest API не заменяются обычным OpenXR Runtime.")
  if(route!=ApkLaunchRoute.OPENXR_RUNTIME_CANDIDATE)return blocked(OpenXrLaunchReadinessState.NOT_OPENXR,"В APK не подтверждён OpenXR-маршрут.")
  if(!isInstalled)return blocked(OpenXrLaunchReadinessState.INSTALL_REQUIRED,"Сначала установи APK через Android.")
  return when(runtime.state){
   OpenXrRuntimeDiscoveryState.ACTIVE_RUNTIME_FOUND->OpenXrLaunchReadiness(OpenXrLaunchReadinessState.READY_TO_HAND_OFF,true,"Runtime и его библиотека найдены. Успешная XR-инициализация ещё не проверена.")
   OpenXrRuntimeDiscoveryState.RUNTIME_LIBRARY_MISSING->blocked(OpenXrLaunchReadinessState.RUNTIME_LIBRARY_UNAVAILABLE,runtime.message)
   else->blocked(OpenXrLaunchReadinessState.ACTIVE_RUNTIME_REQUIRED,runtime.message)
  }
 }
}
