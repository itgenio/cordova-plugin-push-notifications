package notifications

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FCMService"
        private const val DEFAULT_CHANNEL_ID = "123"
        private const val HIGH_PRIORITY_CHANNEL_ID = "234"
        private const val CONNECT_TIMEOUT = 15000
        private const val READ_TIMEOUT = 15000
    }

    private val manifestIconKey = "com.google.firebase.messaging.default_notification_icon"
    private val manifestChannelKey = "com.google.firebase.messaging.default_notification_channel_id"
    private val manifestColorKey = "com.google.firebase.messaging.default_notification_color"

    private var defaultNotificationIcon = 0
    private var defaultNotificationColor = 0
    private var defaultNotificationChannelID = ""
    private var notificationManager: NotificationManager? = null
    private var mainActivity: Class<*>? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)

        try {
            // Get MainActivity class dynamically
            val launchIntent = packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            val className = launchIntent?.component?.className
            if (className != null) {
                mainActivity = Class.forName(className)
            }

            // Load metadata from manifest
            val ai = packageManager.getApplicationInfo(
                applicationContext.packageName,
                PackageManager.GET_META_DATA
            )

            defaultNotificationChannelID = ai.metaData?.getString(manifestChannelKey) ?: DEFAULT_CHANNEL_ID
            defaultNotificationIcon = ai.metaData?.getInt(manifestIconKey) ?: ai.icon
            defaultNotificationColor = ai.metaData?.getInt(manifestColorKey) ?: 0

            // Create notification channels
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannels()
            }

            Log.d(TAG, "Firebase Messaging Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase Messaging Service", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val defaultChannel = NotificationChannel(
                defaultNotificationChannelID,
                "Standard notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "General push notifications"
                enableVibration(true)
                enableLights(true)
            }

            val highPriorityChannel = NotificationChannel(
                HIGH_PRIORITY_CHANNEL_ID,
                "Important notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority notifications"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }

            notificationManager?.apply {
                createNotificationChannel(defaultChannel)
                createNotificationChannel(highPriorityChannel)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "Message received from: ${remoteMessage.from}")
        Log.d(TAG, "Message data: ${remoteMessage.data}")

        val isVisible = isForeground()

        if (isVisible) {
            Log.i(TAG, "App is foreground - skip sending message")
        } else {
            Log.i(TAG, "App is background - sending message")

            // IMPORTANT: Always process the data payload, regardless of the notification field.
            if (remoteMessage.data.isNotEmpty()) {
                handleDataPayload(remoteMessage.data)
            }

            // If a notification field is also present (should not be the case with data_only=true)
            remoteMessage.notification?.let {
                Log.d(TAG, "Notification payload: ${it.title} - ${it.body}")
            }
        }
    }

    private fun handleDataPayload(data: Map<String, String>) {
        val title = data["title"] ?: "Neue Nachricht"
        val body = data["body"] ?: ""
        val payload = data["payload"]
        val largeIcon = data["large_icon"]
        val image = data["image"]
        val notificationId = data["id"]?.toIntOrNull() ?: System.currentTimeMillis().toInt()
        val channelId = data["channel_id"] ?: defaultNotificationChannelID

        Log.d(TAG, "Creating notification - Title: $title, Body: $body, ID: $notificationId")

        // Loading images asynchronously in the background
        CoroutineScope(Dispatchers.IO).launch {
            val largeIconBitmap = largeIcon?.let { downloadBitmap(it) }
            val imageBitmap = image?.let { downloadBitmap(it) }

            withContext(Dispatchers.Main) {
                showNotification(
                    title = title,
                    body = body,
                    payload = payload,
                    largeIconBitmap = largeIconBitmap,
                    imageBitmap = imageBitmap,
                    notificationId = notificationId,
                    channelId = channelId
                )
            }
        }
    }

    private fun showNotification(
        title: String,
        body: String,
        payload: String?,
        largeIconBitmap: Bitmap?,
        imageBitmap: Bitmap?,
        notificationId: Int,
        channelId: String
    ) {
        // Create intent for notification tap
        val resultIntent = Intent(this, mainActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            payload?.let {
                putExtra("pushNotification", it)
            }
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val resultPendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(resultIntent)
            getPendingIntent(notificationId, pendingIntentFlags)
        }

        // Sound URI
        val soundUri = try {
            Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${applicationContext.packageName}/raw/$channelId")
        } catch (e: Exception) {
            Log.w(TAG, "Custom sound not found, using default", e)
            null
        }

        // Build notification with dynamic priority based on channel
        val priority = if (channelId == HIGH_PRIORITY_CHANNEL_ID) {
            NotificationCompat.PRIORITY_MAX
        } else {
            NotificationCompat.PRIORITY_HIGH
        }

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(defaultNotificationIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(priority)  // ← Dynamic Priority
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)  // ← Important for Heads-Up
            .setAutoCancel(true)
            .setContentIntent(resultPendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 250, 500))  // ← Prolonged vibration
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // ← On lock screen
            .setFullScreenIntent(resultPendingIntent, true)  // ← Force a heads-up!

        // Set color if available
        if (defaultNotificationColor != 0) {
            notificationBuilder.setColor(defaultNotificationColor)
        }

        // Set custom sound if available
        soundUri?.let {
            notificationBuilder.setSound(it)
        }

        // Add large icon if available
        largeIconBitmap?.let {
            notificationBuilder.setLargeIcon(it)
        }

        // Add big picture if available
        imageBitmap?.let {
            notificationBuilder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(it)
                    .bigLargeIcon(null as Bitmap?) // Hide large icon when expanded
            )
        } ?: run {
            // Use BigTextStyle if no image
            notificationBuilder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(body)
            )
        }

        // Show notification
        try {
            with(NotificationManagerCompat.from(this)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
                        notify(notificationId, notificationBuilder.build())
                        Log.d(TAG, "Notification shown with ID: $notificationId")
                    } else {
                        Log.w(TAG, "Notification permission not granted")
                    }
                } else {
                    notify(notificationId, notificationBuilder.build())
                    Log.d(TAG, "Notification shown with ID: $notificationId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }

    private suspend fun downloadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading image from: $url")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doInput = true
                connect()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                connection.disconnect()
                Log.d(TAG, "Image downloaded successfully")
                bitmap
            } else {
                Log.w(TAG, "Failed to download image, response code: ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error downloading image from $url", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error downloading image", e)
            null
        }
    }

    private fun isForeground(): Boolean {
        val appProcessInfo = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)

        val importance = appProcessInfo.importance

        return importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        // TODO: Send the new token to your server
        // sendTokenToServer(token)
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
        Log.d(TAG, "Messages deleted on server")
    }
}
