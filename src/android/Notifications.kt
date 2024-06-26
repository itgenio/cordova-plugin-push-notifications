package notifications

import android.util.Log
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.json.JSONArray
import org.json.JSONException

class Notifications: CordovaPlugin() {
  companion object {
    private const val TAG = "pushNotification"
  }
  var lastTapedNotification = ""

  override fun pluginInitialize() {
    val activity = cordova.activity
    val extras = activity.intent.extras
    if (extras != null) {
      val payload = extras.getString("pushNotification")
      if (payload != null) {
        lastTapedNotification = payload
      }
    }
    super.pluginInitialize()
  }

  @Throws(JSONException::class)
  override fun execute(action: String, data: JSONArray, callbackContext: CallbackContext): Boolean {
    val context = callbackContext
    var result = true
    try {
      when (action) {
        "registration" -> {
          cordova.threadPool.execute {
            getFirebaseToken(context)
          }
        }
        "tapped" -> {
          cordova.threadPool.execute {
            val res = lastTapedNotification
            lastTapedNotification = ""
            context.success(res)
          }
        }
        else -> {
          handleError("Invalid action", context)
          result = false
        }
      }
    } catch (e: Exception) {
      handleError(e.toString(), context)
      result = false
    }

    return result
  }

  private fun getFirebaseToken(context: CallbackContext) {
    FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
        if (!task.isSuccessful) {
            Log.w(TAG, "Fetching FCM registration token failed", task.exception)
            return@OnCompleteListener
        }
        val token = task.result
        context.success(token)
        Log.d(TAG, token)
    })
  }

  private fun handleError(errorMsg: String, context: CallbackContext) {
    try {
      Log.e(TAG, errorMsg)
      context.error(errorMsg)
    } catch (e: Exception) {
      Log.e(TAG, e.toString())
    }
  }
}
