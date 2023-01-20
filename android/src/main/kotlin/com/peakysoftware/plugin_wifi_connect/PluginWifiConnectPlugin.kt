package com.peakysoftware.plugin_wifi_connect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.NonNull
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat.startActivity
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** PluginWifiConnectPlugin */
@SuppressLint("WifiManagerPotentialLeak")
class PluginWifiConnectPlugin : FlutterPlugin, MethodCallHandler {
  // / The MethodChannel that will the communication between Flutter and native Android
  // /
  // / This local reference serves to register the plugin with the Flutter Engine and unregister it
  // / when the Flutter Engine is detached from the Activity
  private lateinit var channel: MethodChannel
  private lateinit var context: Context

  // holds the network id returned by WifiManager.addNetwork, required to disconnect (API < 29)
  private var networkId: Int? = null

  private val connectivityManager: ConnectivityManager by lazy(LazyThreadSafetyMode.NONE) {
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  }

  private val wifiManager: WifiManager by lazy(LazyThreadSafetyMode.NONE) {
    context.getSystemService(Context.WIFI_SERVICE) as WifiManager
  }

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    context = flutterPluginBinding.applicationContext
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "plugin_wifi_connect")
    channel.setMethodCallHandler(this)
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    when (call.method) {
      "disconnect" -> {
        when {
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            result.success(disconnect())
            return
          }
          else -> {
            disconnect(result)
            return
          }
        }
      }
      "getSSID" -> {
        result.success(getSSID())
        return
      }
      "connect" -> {
        if (!wifiManager.isWifiEnabled) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivity(
              context,
              Intent(Settings.Panel.ACTION_WIFI).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
              null
            )
          } else {
            startActivity(context,
              Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
              null
            )
          }
          result.success(false)
          return
        }

        val ssid = call.argument<String>("ssid")
        ssid?.let {
          when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
              val wifiNetworkSuggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .build()
              connect(wifiNetworkSuggestion, result)
              return
            }
            else -> {
              val wifiConfig = createWifiConfig(it)
              connect(wifiConfig, result)
              return
            }
          }
        }
        return
      }
      "secureConnect" -> {
        val ssid = call.argument<String>("ssid")
        val password = call.argument<String>("password")
        val isWep = call.argument<Boolean>("isWep")

        if (ssid == null || password == null || isWep == null) {
          result.success(false)
          return
        }

        if (!wifiManager.isWifiEnabled) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivity(
              context,
              Intent(Settings.Panel.ACTION_WIFI).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
              null
            )
          } else {
            startActivity(context,
              Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
              null
            )
          }
          result.success(false)
          return
        }

        if (isWep || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
          val wifiConfig = isWep.let {
            if (it) {
              createWEPConfig(ssid, password)
            } else {
              createWifiConfig(ssid, password)
            }
          }
          connect(wifiConfig, result)
          return
        }

        val wifiNetworkSuggestion = WifiNetworkSuggestion.Builder()
          .setSsid(ssid)
          .setWpa2Passphrase(password)
          .build()
        connect(wifiNetworkSuggestion, result)
        return
      }
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  @Suppress("DEPRECATION")
  fun createWifiConfig(@NonNull ssid: String): WifiConfiguration {
    return WifiConfiguration().apply {
      SSID = "\"" + ssid + "\""
      allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)

      allowedProtocols.set(WifiConfiguration.Protocol.RSN)
      allowedProtocols.set(WifiConfiguration.Protocol.WPA)

      allowedAuthAlgorithms.clear()

      allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
      allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)

      allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
      allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
      allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
      allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
    }
  }

  @Suppress("DEPRECATION")
  fun createWifiConfig(@NonNull ssid: String, @NonNull password: String): WifiConfiguration {
    return createWifiConfig(ssid).apply {
      preSharedKey = "\"" + password + "\""
      status = WifiConfiguration.Status.ENABLED

      allowedKeyManagement.clear()
      allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
    }
  }

  @Suppress("DEPRECATION")
  fun createWEPConfig(@NonNull ssid: String, @NonNull password: String): WifiConfiguration {
    return createWifiConfig(ssid).apply {
      wepKeys[0] = "\"" + password + "\""
      wepTxKeyIndex = 0

      allowedGroupCiphers.clear()
      allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
      allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)

      allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
      allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
    }
  }

  @Suppress("DEPRECATION")
  fun connect(@NonNull wifiConfiguration: WifiConfiguration, @NonNull result: Result) {
    val network = wifiManager.addNetwork(wifiConfiguration)
    if (network == -1) {
      result.success(false)
      return
    }
    wifiManager.saveConfiguration()

    val wifiChangeReceiver = object : BroadcastReceiver() {
      var count = 0
      override fun onReceive(context: Context, intent: Intent) {
        count++
        val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
        if (info != null && info.isConnected) {
          if (info.extraInfo == wifiConfiguration.SSID || getSSID() == wifiConfiguration.SSID) {
            result.success(true)
            context.unregisterReceiver(this)
          } else if (count > 1) {
            // Ignore first callback if not success. It may be for the already connected SSID
            result.success(false)
            context.unregisterReceiver(this)
          }
        }
      }
    }

    val intentFilter = IntentFilter()
    intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
    context.registerReceiver(wifiChangeReceiver, intentFilter)

    // enable the new network and attempt to connect to it 
    wifiManager.enableNetwork(network, true)
    networkId = network
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun connect(suggestion: WifiNetworkSuggestion, result: Result) {
    val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
    if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
      result.success(true)
    } else {
      result.success(false)
    }
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  fun disconnect(): Boolean {
    connectivityManager.bindProcessToNetwork(null)
    return true
  }

  @Suppress("DEPRECATION")
  fun disconnect(@NonNull result: Result) {
    val network = networkId
    if (network == null) {
      result.success(false)
      return
    }
    val wifiChangeReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
        if (info != null && !info.isConnected) {
          result.success(true)
          context.unregisterReceiver(this)
        }
      }
    }

    val intentFilter = IntentFilter()
    intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
    context.registerReceiver(wifiChangeReceiver, intentFilter)
    // remove network to emulate a behavior as close as possible to new Android API
    wifiManager.removeNetwork(network)
    wifiManager.reconnect()
    networkId = null
  }

  fun getSSID(): String = wifiManager.connectionInfo.ssid
}
