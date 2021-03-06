package spartons.com.prosmssenderapp.activities.sendBulkSms.viewModel

import android.annotation.SuppressLint
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.opencsv.CSVReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import spartons.com.prosmssenderapp.R
import spartons.com.prosmssenderapp.activities.sendBulkSms.data.toSmsContact
import spartons.com.prosmssenderapp.backend.MyCustomApplication
import spartons.com.prosmssenderapp.helper.NotificationIdHelper
import spartons.com.prosmssenderapp.helper.SharedPreferenceHelper
import spartons.com.prosmssenderapp.helper.SharedPreferenceHelper.Companion.BULKS_SMS_PREVIOUS_WORKER_ID
import spartons.com.prosmssenderapp.helper.SharedPreferenceHelper.Companion.BULK_SMS_PREFERRED_CARRIER_NUMBER
import spartons.com.prosmssenderapp.helper.SharedPreferenceHelper.Companion.BULK_SMS_PREFERRED_MULTIPLE_CARRIER_NUMBER_FLAG
import spartons.com.prosmssenderapp.roomPersistence.BulkSms
import spartons.com.prosmssenderapp.roomPersistence.BulkSmsDao
import spartons.com.prosmssenderapp.util.Constants.CARRIER_NAME_SPLITTER
import spartons.com.prosmssenderapp.util.Event
import spartons.com.prosmssenderapp.util.enqueueWorker
import spartons.com.prosmssenderapp.util.subscriptionManager
import spartons.com.prosmssenderapp.workers.SendBulkSmsWorker
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException


/**
 * Ahsen Saeed}
 * ahsansaeed067@gmail.com}
 * 6/26/19}
 */

@SuppressLint("MissingPermission")
class SendBulkSmsViewModel constructor(
    application: MyCustomApplication,
    private val sharedPreferenceHelper: SharedPreferenceHelper,
    private val bulkSmsDao: BulkSmsDao
) : AndroidViewModel(application) {

    private val coroutineContext = Dispatchers.Default + SupervisorJob()

    private val _uiState = MutableLiveData<SendBulkSmsUiModel>()
    val uiState: LiveData<SendBulkSmsUiModel> = _uiState

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP_MR1)
    fun handleDeviceCarrierNumbers() {
        if (sharedPreferenceHelper.getString(BULK_SMS_PREFERRED_CARRIER_NUMBER) != null && sharedPreferenceHelper.getBoolean(
                BULK_SMS_PREFERRED_MULTIPLE_CARRIER_NUMBER_FLAG
            )
        ) return
        val subscriptionManager = getApplication<MyCustomApplication>().subscriptionManager
        val allCarrierNumbers = subscriptionManager.activeSubscriptionInfoList.map {
            it.subscriptionId.toString()
                .plus("$CARRIER_NAME_SPLITTER ${it.carrierName}(${it.number})")
        }
        viewModelScope.launch(coroutineContext) {
            when {
                allCarrierNumbers.isEmpty() -> emitUiState(noDeviceNumber = true)
                allCarrierNumbers.count() > 1 -> emitUiState(
                    showMultipleCarrierNumber = Event(allCarrierNumbers)
                )
                else -> sharedPreferenceHelper.put(
                    BULK_SMS_PREFERRED_CARRIER_NUMBER, allCarrierNumbers.component1()
                )
            }
        }
    }

    private suspend fun emitUiState(
        showProgress: Boolean = false, contactList: Event<List<String>>? = null,
        showMessage: Event<Int>? = null, noDeviceNumber: Boolean = false,
        showMultipleCarrierNumber: Event<List<String>>? = null
    ) = withContext(Dispatchers.Main) {
        SendBulkSmsUiModel(
            showProgress, contactList,
            showMessage, noDeviceNumber, showMultipleCarrierNumber
        ).also {
            _uiState.value = it
        }
    }

    fun handleSelectedFile(selectedFile: File) {

        viewModelScope.launch(coroutineContext) {
            Timber.d("file $selectedFile")
            if(selectedFile.name.endsWith(".txt")){
                Timber.d("ends with txt")
                emitUiState(showProgress = true)
                BufferedReader(FileReader(selectedFile)).use {
                    val filteredContactList = it.readLines()
                        .filter { contactNumber ->
                            contactNumber.length > 4
                        }
                    if (filteredContactList.isNotEmpty())
                        emitUiState(contactList = Event(filteredContactList))
                    else
                        emitUiState(showMessage = Event(R.string.the_selected_file_is_empty))
                }
            }else if(selectedFile.name.endsWith(".csv")){

                val filteredContactList = arrayListOf<String>()
                Timber.d("ends with csv")
                try {
                    val reader = CSVReader(FileReader(selectedFile))
                    var nextLine: Array<String>

                    //val header = reader.readNext()

                    // Read the rest
                    var line: Array<String>? = reader.readNext()
                    while (line != null) {
                        // Do something with the data
                        Timber.d("line is ${line[0]}")

                        filteredContactList.add(line[0])

                        line = reader.readNext()
                    }

                    if (filteredContactList.isNotEmpty())
                        emitUiState(contactList = Event(filteredContactList))
                    else
                        emitUiState(showMessage = Event(R.string.the_selected_file_is_empty))

//                    while (reader.readNext().also { nextLine = it } != null) {
//                        // nextLine[] is an array of values from the line
//                        //println(nextLine[0] + nextLine[1] + "etc...")
//                        //Timber.d("line is ${nextLine[0]}")
//
//                    }
                } catch (e: IOException) {
                    Timber.d("$e")
                }

            }
        }
    }

    fun checkIfWorkerIsIdle() =
        sharedPreferenceHelper.getString(BULKS_SMS_PREVIOUS_WORKER_ID) == null

    fun sendBulkSms(contactList: Array<String>, smsContent: String, smsTitle: String) {
        viewModelScope.launch(coroutineContext) {
            /*val carrierName =
                sharedPreferenceHelper.getString(BULK_SMS_PREFERRED_CARRIER_NUMBER)?.split(
                    CARRIER_NAME_SPLITTER
                )?.get(1) ?: ""*/
            val carrierName = smsTitle
            val bulkSms = BulkSms(
                smsContacts = contactList.map { it.toSmsContact() }.toList(),
                smsContent = smsContent, startDateTime = System.currentTimeMillis(),
                carrierName = carrierName
            )
            val rowId = bulkSmsDao.insert(bulkSms)
            val worker = getApplication<MyCustomApplication>().enqueueWorker<SendBulkSmsWorker> {
                setInputData(
                    SendBulkSmsWorker.constructWorkerParams(
                        rowId, NotificationIdHelper.getId()
                    )
                )
            }
            sharedPreferenceHelper.put(BULKS_SMS_PREVIOUS_WORKER_ID, worker.id.toString())
        }
    }



}

data class SendBulkSmsUiModel(
    val showProgress: Boolean,
    val contactList: Event<List<String>>?,
    val showMessage: Event<Int>?,
    val noDeviceNumber: Boolean,
    val showMultipleCarrierNumber: Event<List<String>>?
)