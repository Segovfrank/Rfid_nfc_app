package com.example.rfid_nfc_app

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentFilter.MalformedMimeTypeException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_nfc_app.databinding.ActivityMainBinding
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val TAG = "nfcinventory_simple"

    // NFC-related variables
    var mNfcAdapter: NfcAdapter? = null
    var mNfcPendingIntent: PendingIntent? = null
    lateinit var mReadTagFilters: Array<IntentFilter>
    lateinit var mWriteTagFilters: Array<IntentFilter>
    private var mWriteMode = false

    private lateinit var mWriteTagDialog: AlertDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
    }

    private fun init(){

        if(mNfcAdapter == null){
            Toast.makeText(this,
                    "Your device does not support NFC. Cannot run demo.",
                    Toast.LENGTH_LONG).show()
            //finish()
            //return
        }

        // check if NFC is enabled
        checkNfcEnabled()
        // Handle foreground NFC scanning in this activity by creating a
        // PendingIntent with FLAG_ACTIVITY_SINGLE_TOP flag so each new scan
        // is not added to the Back Stack

        mNfcPendingIntent = PendingIntent.getActivity(this, 0, Intent(this,
                javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0)
        // Create intent filter to handle NDEF NFC tags detected from inside our
        // application when in "read mode":

        val ndefDetected = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            ndefDetected.addDataType("application/root.gast.playground.nfc")
        } catch (e: MalformedMimeTypeException) {
            throw RuntimeException("Could not add MIME type.", e)
        }
        // Create intent filter to detect any NFC tag when attempting to write
        // to a tag in "write mode"
        val tagDetected = IntentFilter(
                NfcAdapter.ACTION_TAG_DISCOVERED)
        // create IntentFilter arrays:
        mWriteTagFilters = arrayOf(tagDetected)
        mReadTagFilters = arrayOf(ndefDetected, tagDetected)

    }

    override fun onResume() {
        super.onResume()
        // Double check if NFC is enabled
        // Double check if NFC is enabled
        checkNfcEnabled()
        Log.d(TAG, "onResume: $intent")
        if (intent.action != null) {
            // tag received when app is not running and not in the foreground:
            if (intent.action ==
                    NfcAdapter.ACTION_NDEF_DISCOVERED) {
                val msgs: Array<NdefMessage?> = getNdefMessagesFromIntent(intent)
                val record = msgs[0]?.records?.get(0)
                val payload = record?.payload
                payload?.let { String(it) }?.let { setTextFieldValues(it) }
            }
        }
        // Enable priority for current activity to detect scanned tags
        // enableForegroundDispatch( activity, pendingIntent,
        // intentsFiltersArray, techListsArray );
        // Enable priority for current activity to detect scanned tags
        // enableForegroundDispatch( activity, pendingIntent,
        // intentsFiltersArray, techListsArray );
        mNfcAdapter?.enableForegroundDispatch(this, mNfcPendingIntent, mReadTagFilters, null)

    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause: " + getIntent());
        mNfcAdapter?.disableForegroundDispatch(this);
    }

    override fun onNewIntent(intent: Intent?) {
        Log.d(TAG, "onNewIntent: $intent")
        if (!mWriteMode) {
            // Currently in tag READING mode
            if (intent!!.action.equals(NfcAdapter.ACTION_NDEF_DISCOVERED)) {
                val msgs: Array<NdefMessage?> = getNdefMessagesFromIntent(intent)
                msgs[0]?.let { confirmDisplayedContentOverwrite(it) }
            } else if (intent.action.equals(
                            NfcAdapter.ACTION_TAG_DISCOVERED)) {
                Toast.makeText(this,
                        "This NFC tag currently has no inventory NDEF data.",
                        Toast.LENGTH_LONG).show()
            }
        } else {
            // Currently in tag WRITING mode
            if (intent!!.action.equals(NfcAdapter.ACTION_TAG_DISCOVERED)) {
                val detectedTag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
                createNdefFromJson()?.let { writeTag(it, detectedTag) }
                mWriteTagDialog!!.cancel()
            }
        }
        super.onNewIntent(intent)
    }

    fun getNdefMessagesFromIntent(intent: Intent): Array<NdefMessage?> {
        // Parse the intent
        var msgs: Array<NdefMessage?> = arrayOf()
        val action = intent.action
        if (action == NfcAdapter.ACTION_TAG_DISCOVERED || action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            val rawMsgs = intent
                    .getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            if (rawMsgs != null) {
                msgs = arrayOfNulls(rawMsgs.size)
                for (i in rawMsgs.indices) {
                    msgs[i] = rawMsgs[i] as NdefMessage
                }
            } else {
                // Unknown tag type
                val empty = byteArrayOf()
                val record = NdefRecord(NdefRecord.TNF_UNKNOWN,
                        empty, empty, empty)
                val msg = NdefMessage(arrayOf(record))
                msgs = arrayOf(msg)
            }
        } else {
            Log.e(TAG, "Unknown intent.")
            finish()
        }
        return msgs
    }

    private fun confirmDisplayedContentOverwrite(msg: NdefMessage) {
        AlertDialog.Builder(this)
                .setTitle(getString(R.string.new_tag_found))
                .setMessage(getString(R.string.replace_current_tag))
                .setPositiveButton("Yes") { dialog, id -> // use the current values in the NDEF payload
                    // to update the text fields
                    val payload = String(msg.records[0]
                            .payload)
                    setTextFieldValues(payload)
                }
                .setNegativeButton("No") { dialog, id -> dialog.cancel() }.show()
    }

    private fun setTextFieldValues(jsonString: String) {
        var inventory: JSONObject? = null
        var name: String? = ""
        var ram: String? = ""
        var processor: String? = ""
        try {
            inventory = JSONObject(jsonString)
            name = inventory.getString("name")
            ram = inventory.getString("ram")
            processor = inventory.getString("processor")
        } catch (e: JSONException) {
            Log.e(TAG, "Couldn't parse JSON: ", e)
        }
        val nameField = binding.computerName.text
        nameField?.clear()
        nameField?.append(name)
        val ramField = binding.computerRam.text
        ramField?.clear()
        ramField?.append(ram)
        val processorField = binding.computerProcessor.text
        processorField?.clear()
        processorField?.append(processor)
    }

    private val mTagWriter: View.OnClickListener = View.OnClickListener {
        enableTagWriteMode()
        val builder = AlertDialog.Builder(
                this@MainActivity)
                .setTitle(getString(R.string.ready_to_write))
                .setMessage(getString(R.string.ready_to_write_instructions))
                .setCancelable(true)
                .setNegativeButton("Cancel"
                ) { dialog, id -> dialog.cancel() }
                .setOnCancelListener { enableTagReadMode() }
        mWriteTagDialog = builder.create()
        mWriteTagDialog?.show()
    }

    private fun createNdefFromJson(): NdefMessage? {
        // get the values from the form's text fields:
        val nameField = binding.computerName.text
        val ramField = binding.computerRam.text
        val processorField = binding.computerProcessor.text
        // create a JSON object out of the values:
        val computerSpecs = JSONObject()
        try {
            computerSpecs.put("name", nameField)
            computerSpecs.put("ram", ramField)
            computerSpecs.put("processor", processorField)
        } catch (e: JSONException) {
            Log.e(TAG, "Could not create JSON: ", e)
        }
        // create a new NDEF record and containing NDEF message using the app's
        // custom MIME type:
        val mimeType = "application/root.gast.playground.nfc"
        val mimeBytes: ByteArray = mimeType.toByteArray(Charset.forName("UTF-8"))

        val data = computerSpecs.toString()
        val dataBytes: ByteArray = data.toByteArray(Charset.forName("UTF-8"))
        val id = ByteArray(0)
        val record = NdefRecord(NdefRecord.TNF_MIME_MEDIA,
                mimeBytes, id, dataBytes)
        // return the NDEF message
        return NdefMessage(arrayOf(record))
    }

    private fun enableTagWriteMode() {
        mWriteMode = true
        mNfcAdapter!!.enableForegroundDispatch(this, mNfcPendingIntent,
                mWriteTagFilters, null)
    }

    private fun enableTagReadMode() {
        mWriteMode = false
        mNfcAdapter!!.enableForegroundDispatch(this, mNfcPendingIntent,
                mReadTagFilters, null)
    }

    fun writeTag(message: NdefMessage, tag: Tag?): Boolean {
        val size = message.toByteArray().size
        try {
            val ndef = Ndef.get(tag)
            return if (ndef != null) {
                ndef.connect()
                if (!ndef.isWritable) {
                    Toast.makeText(this,
                            "Cannot write to this tag. This tag is read-only.",
                            Toast.LENGTH_LONG).show()
                    return false
                }
                if (ndef.maxSize < size) {
                    Toast.makeText(
                            this,
                            "Cannot write to this tag. Message size (" + size
                                    + " bytes) exceeds this tag's capacity of "
                                    + ndef.maxSize + " bytes.",
                            Toast.LENGTH_LONG).show()
                    return false
                }
                ndef.writeNdefMessage(message)
                Toast.makeText(this,
                        "A pre-formatted tag was successfully updated.",
                        Toast.LENGTH_LONG).show()
                true
            } else {
                val format = NdefFormatable.get(tag)
                if (format != null) {
                    try {
                        format.connect()
                        format.format(message)
                        Toast.makeText(
                                this,
                                "This tag was successfully formatted and updated.",
                                Toast.LENGTH_LONG).show()
                        true
                    } catch (e: IOException) {
                        Toast.makeText(
                                this,
                                "Cannot write to this tag due to I/O Exception.",
                                Toast.LENGTH_LONG).show()
                        false
                    }
                } else {
                    Toast.makeText(
                            this,
                            "Cannot write to this tag. This tag does not support NDEF.",
                            Toast.LENGTH_LONG).show()
                    false
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this,
                    "Cannot write to this tag due to an Exception.",
                    Toast.LENGTH_LONG).show()
        }
        return false
    }


    private fun checkNfcEnabled() {
        val nfcEnabled = if(mNfcAdapter == null){
            false
        }else {
            mNfcAdapter!!.isEnabled
        }
        if (!nfcEnabled) {
            AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.warning_nfc_is_off))
                    .setMessage(getString(R.string.turn_on_nfc))
                    .setCancelable(false)
                    .setPositiveButton("Update Settings"
                    ) { dialog, id ->
                        startActivity(Intent(
                                Settings.ACTION_WIRELESS_SETTINGS))
                    }.create().show()
        }
    }


}
