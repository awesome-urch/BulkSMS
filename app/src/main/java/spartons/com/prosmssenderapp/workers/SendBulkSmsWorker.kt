package spartons.com.prosmssenderapp.workers

import android.content.Context
import android.media.RingtoneManager
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import org.koin.core.KoinComponent
import org.koin.core.inject
import retrofit2.Retrofit
import spartons.com.prosmssenderapp.BuildConfig.ACCOUNT_SID
import spartons.com.prosmssenderapp.BuildConfig.AUTH_TOKEN
import spartons.com.prosmssenderapp.R
import spartons.com.prosmssenderapp.backend.MyCustomApplication.Companion.APPLICATION_CHANNEL
import spartons.com.prosmssenderapp.helper.NotificationIdHelper
import spartons.com.prosmssenderapp.helper.SharedPreferenceHelper
import spartons.com.prosmssenderapp.helper.SharedPreferenceHelper.Companion.BULKS_SMS_PREVIOUS_WORKER_ID
import spartons.com.prosmssenderapp.helper.SharedPreferenceHelper.Companion.BULK_SMS_PREFERRED_CARRIER_NUMBER
import spartons.com.prosmssenderapp.roomPersistence.BulkSmsDao
import spartons.com.prosmssenderapp.roomPersistence.BulkSmsStatus
import spartons.com.prosmssenderapp.util.Constants
import spartons.com.prosmssenderapp.util.Constants.BASE_URL
import spartons.com.prosmssenderapp.util.notificationBuilder
import spartons.com.prosmssenderapp.util.notificationManager
import timber.log.Timber


/**
 * Ahsen Saeed}
 * ahsansaeed067@gmail.com}
 * 6/27/19}
 */

class SendBulkSmsWorker constructor(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters), KoinComponent {

    private val bulkSmsDao: BulkSmsDao by inject()
    private val sharedPreferenceHelper: SharedPreferenceHelper by inject()
    private val notificationId = inputData.getInt(NOTIFICATION_ID, NotificationIdHelper.getId())
    private val subscriptionInfoId =
        sharedPreferenceHelper.getString(BULK_SMS_PREFERRED_CARRIER_NUMBER)?.split(
            Constants.CARRIER_NAME_SPLITTER
        )?.component1()?.toInt()
    private val smsManager =
        if (subscriptionInfoId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionInfoId) else SmsManager.getDefault()
    private val rowId = inputData.getLong(BULK_SMS_ROW_ID, -1)
    private val notificationManager = context.notificationManager
    private val notificationBuilder =
        context.notificationBuilder(APPLICATION_CHANNEL) {
            setSmallIcon(R.mipmap.ic_launcher_round)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            setContentTitle(SENDING_BULK_SMS)
            setAutoCancel(false)
            setOnlyAlertOnce(true)
            setOngoing(true)
        }

    private fun setNotificationCompleteStatus() {
        notificationBuilder.setProgress(0, 0, false)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentTitle("All sms have been sent").also {
                notificationManager.notify(notificationId, it.build())
            }
    }

    companion object {
        private const val SENDING_BULK_SMS = "Sending Bulk Sms"
        private const val BULK_SMS_ROW_ID =
            "spartons.com.prosmssenderapp.workers.SendBulkSmsWorker.BULK_SMS_ROW_ID"
        private const val NOTIFICATION_ID =
            "spartons.com.prosmssenderapp.workers.SendBulkSmsWorker.NOTIFICATION_ID"
        private const val SMS_CONTENT_LENGTH_LIMIT = 140

        fun constructWorkerParams(rowId: Long, notificationId: Int): Data =
            Data.Builder()
                .putLong(BULK_SMS_ROW_ID, rowId)
                .putInt(NOTIFICATION_ID, notificationId)
                .build()
    }

    override suspend fun doWork(): Result {

        /*curl -X POST https://api.twilio.com/2010-04-01/Accounts/$TWILIO_ACCOUNT_SID/Messages.json \
        --data-urlencode "From=+15017122661" \
        --data-urlencode "Body=body" \
        --data-urlencode "To=+15558675310" \
        -u $TWILIO_ACCOUNT_SID:$TWILIO_AUTH_TOKEN*/

//        val client: OkHttpClient = Builder()
//            .addInterceptor(BasicAuthInterceptor(username, password))
//            .build()

        Timber.e("Worker starts")
        val bulkSms = bulkSmsDao.bulkSmsWithRowId(rowId)
        val smsContacts = bulkSms.smsContacts
        val contactListSize = smsContacts.count()
        val smsContent = bulkSms.smsContent
        val remainSmsSentNumbers = smsContacts.filter { !it.isSent }
        var smsCountProgress = contactListSize - remainSmsSentNumbers.count()

        Timber.d("content is $smsContent && bulk is $bulkSms")

        for (smsContact in remainSmsSentNumbers) {

            val loggingInterceptor = HttpLoggingInterceptor()
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY)

            val client: OkHttpClient = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .addInterceptor(
                    BasicAuthInterceptor(
                        ACCOUNT_SID,
                        AUTH_TOKEN
                    )
                ).addInterceptor(Interceptor { chain ->
                    val newRequest: Request = chain.request().newBuilder()
                        .build()
                    chain.proceed(newRequest)
                }).build()

            val retrofit: Retrofit = Retrofit.Builder()
                .client(client)
                .baseUrl(BASE_URL)
                //.addConverterFactory(GsonConverterFactory.create())
                .build()

            // Create Service
            val service = retrofit.create(APIService::class.java)

            CoroutineScope(Dispatchers.IO).launch {

                val response = service.pushSms("Danziga",smsContact.contactNumber,smsContent)

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {

                        // Convert raw JSON to pretty JSON using GSON library
                        val gson = GsonBuilder().setPrettyPrinting().create()
                        val prettyJson = gson.toJson(
                            JsonParser.parseString(
                                response.body()
                                    ?.string() // About this thread blocking annotation : https://github.com/square/retrofit/issues/3255
                            )
                        )

                        Timber.d("Pretty Printed JSON : $prettyJson")

                    } else {

                        Timber.d("RETROFIT_ERROR ${response.code()}")

                    }
                }
            }

            notificationBuilder.setProgress(contactListSize, ++smsCountProgress, false)
                .setContentTitle(SENDING_BULK_SMS.plus(" $smsCountProgress/$contactListSize"))
                .also {
                    notificationManager.notify(notificationId, it.build())
                }
            smsContact.isSent = true
            bulkSmsDao.update(smsContacts, rowId)
        }


        Timber.e("Worker ends")
        setNotificationCompleteStatus()
        val endTime = System.currentTimeMillis()
        bulkSmsDao.update(endTime, BulkSmsStatus.COMPLETE, rowId)
        sharedPreferenceHelper.put(BULKS_SMS_PREVIOUS_WORKER_ID, null)
        Timber.e("Worker ends")
        return Result.success()


        /*Timber.e("Worker starts")
        val bulkSms = bulkSmsDao.bulkSmsWithRowId(rowId)
        val smsContacts = bulkSms.smsContacts
        val contactListSize = smsContacts.count()
        val remainSmsSentNumbers = smsContacts.filter { !it.isSent }
        var smsCountProgress = contactListSize - remainSmsSentNumbers.count()
        notificationManager.notify(
            notificationId, notificationBuilder.setProgress(
                contactListSize, smsCountProgress, false
            ).build()
        )
        val smsDelayValue =
            (sharedPreferenceHelper.getInt(BULK_SMS_MESSAGE_DELAY_SECONDS).toLong()) * 1000
        val smsContent = bulkSms.smsContent
        val smsDivides = smsManager.divideMessage(smsContent)
        val sourceSmsAddress = sharedPreferenceHelper.getString(BULK_SMS_PREFERRED_CARRIER_NUMBER)
        Timber.e("Source sms address -> $sourceSmsAddress and content -> $smsContent")
        for (smsContact in remainSmsSentNumbers) {
            if (smsContent.length < SMS_CONTENT_LENGTH_LIMIT)
                smsManager.sendTextMessage(
                    smsContact.contactNumber, null, smsContent, null, null
                )
            else
                smsManager.sendMultipartTextMessage(
                    smsContact.contactNumber, null, smsDivides, null, null
                )
            delay(smsDelayValue)
            notificationBuilder.setProgress(contactListSize, ++smsCountProgress, false)
                .setContentTitle(SENDING_BULK_SMS.plus(" $smsCountProgress/$contactListSize"))
                .also {
                    notificationManager.notify(notificationId, it.build())
                }
            smsContact.isSent = true
            bulkSmsDao.update(smsContacts, rowId)
        }
        setNotificationCompleteStatus()
        val endTime = System.currentTimeMillis()
        bulkSmsDao.update(endTime, BulkSmsStatus.COMPLETE, rowId)
        sharedPreferenceHelper.put(BULKS_SMS_PREVIOUS_WORKER_ID, null)
        Timber.e("Worker ends")
        return Result.success()*/
    }

}
