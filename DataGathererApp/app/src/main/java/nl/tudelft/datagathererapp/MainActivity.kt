package nl.tudelft.datagathererapp

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random


class MainActivity : AppCompatActivity() {

    private lateinit var ipEditText: EditText
    private lateinit var sendPacketsButton: Button
    private lateinit var downloadButton: Button
    private lateinit var stopButton: Button
    private lateinit var providerSpinner: Spinner
    private lateinit var deferredRun: Deferred<Unit>
    private lateinit var selectedProvider: String
    private var runType: RunType = RunType.LONG
    private val builder: StringBuilder = java.lang.StringBuilder()
    private val saveCsvLauncher = registerForActivityResult(CreateDocument("text/csv")) { uri ->
        uri?.let { documentUri ->
            contentResolver.openOutputStream(documentUri)?.use { outputStream ->
                val csvContent = getCSVData()
                outputStream.write(csvContent.toByteArray())
                Toast.makeText(this, "CSV file saved successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipEditText = findViewById(R.id.ipEditText)
        sendPacketsButton = findViewById(R.id.sendPacketsButton)
        downloadButton = findViewById(R.id.downloadButton)
        stopButton = findViewById(R.id.stop)

        sendPacketsButton.setOnClickListener {
            builder.clear()
            builder.append("Message id, Time, Sender Port, Receiver Port, Success").appendLine()
            if (!::selectedProvider.isInitialized) {
                Toast.makeText(this@MainActivity, "Please select a provider first!!", Toast.LENGTH_SHORT).show()
            }else {
                deferredRun = GlobalScope.async {
                    sendAllPackets(InetAddress.getByName(ipEditText.text.toString()))
                }
            }
        }

        downloadButton.setOnClickListener {
            downloadCsv()
        }

        stopButton.setOnClickListener {
            if (this::deferredRun.isInitialized && deferredRun.isActive) {
                deferredRun.cancel()
            }

        }
        keepScreenOn()


        providerSpinner = findViewById(R.id.providerSpinner)

        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter.createFromResource(
            this,
            R.array.provider_options,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            // Specify the layout to use when the list of choices appears
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // Apply the adapter to the spinner
            providerSpinner.adapter = adapter
        }

        // Set up an OnItemSelectedListener for the Spinner
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                // Retrieve the selected item
                selectedProvider = parent?.getItemAtPosition(position).toString()

                // Use the selected item as needed
                Toast.makeText(this@MainActivity, "Selected Provider: $selectedProvider", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }

        val runTypeToggle = findViewById<ToggleButton>(R.id.runTypeToggle)

        runTypeToggle.setOnCheckedChangeListener { _, isChecked ->
            runType = if (isChecked) {
                RunType.RAPID
            } else {
                RunType.LONG
            }
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun sendAllPackets(serverAddress: InetAddress) {
        var counter = 0
        var failures: Int

        val (NUMBER_OF_RUNS ,DELAY_BETWEEN_TRANSMISSIONS, DELAY_BETWEEN_RUNS) = getRunConfigs()


        while (counter < NUMBER_OF_RUNS) {
            failures = 0
            val sourcePortsSet = (0..65535).toList().shuffled()
            val destinationPortsSet = (0..65535).toList().shuffled()

            var zippedList = sourcePortsSet.zip(destinationPortsSet)
            var index = 1

            if(DELAY_BETWEEN_TRANSMISSIONS < 300)  zippedList = zippedList.take(1000) // Server can only handle around 1000 transmissions sent every 20ms


            for (pair in zippedList) {
                val sourcePort = pair.component1()
                val destinationPort = pair.component2()

                // Get the numeric ID (replace this with your own logic)
                val numericId = getNumericId()

                println("Sending packet with id: $numericId from port $sourcePort to port $destinationPort packet no: $index")
                GlobalScope.launch(Dispatchers.Unconfined) { // newSingleThreadContext("thread-${sourcePortsSet.size}")
                    val success = sendUdpPacket(
                        numericId,
                        serverAddress,
                        destinationPort,
                        sourcePort,
                    )

                    saveToString(
                        numericId,
                        System.currentTimeMillis(),
                        sourcePort,
                        destinationPort,
                        success
                    )

                    if (!success) failures++
                }
                delay(DELAY_BETWEEN_TRANSMISSIONS)
                index++
            }


            println("Number of failures: $failures")
            counter++
            println("---------------------------")
            println("Run $counter / $NUMBER_OF_RUNS finished!!!!")
            println("---------------------------")
            delay(DELAY_BETWEEN_RUNS) // wait 5 mins, makes sure that maps are clossing
        }
        print("done 1")
        try{
            sendEndMessages(serverAddress)
        }catch (e: Exception){
            println("failed to send end messages")
        }
        print("done 2 ")
    }

    private fun saveToString(numericId: String, time: Long, sourcePort: Int, destinationPort: Int, success: Boolean) {
        builder.append(numericId).append(", ").append(time).append(", ").append(sourcePort)
            .append(", ").append(destinationPort)
        if(!success) builder.append(", F") else builder.append(", S")
        builder.appendLine()
    }

    private fun sendUdpPacket(
        messageBody: String,
        serverAddress: InetAddress,
        serverPort: Int,
        sourcePort: Int,
    ): Boolean {
        var success: Boolean
        try {
            val udpSocket = if(sourcePort >=0) DatagramSocket(sourcePort) else DatagramSocket()
            val sendData = (selectedProvider + "\n" + messageBody).toByteArray(Charsets.UTF_8)

            // Create UDP packet with the destination address and port
            val packet = DatagramPacket(
                sendData,
                sendData.size,
                serverAddress,
                serverPort
            )

            // Send the packet
            udpSocket.send(packet)
            success = true
            udpSocket.close()
        } catch (e: Exception) {
            e.printStackTrace()
            success = false
        }
        return success
    }


    private fun getNumericId(): String {
        return UUID.randomUUID().toString()
    }

    private fun downloadCsv() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm")
            saveCsvLauncher.launch("${selectedProvider}_${runType}_PHONE_${formatter.format(LocalDateTime.now())}.csv")
        } else {
            // For older versions, you can use the previous method
            saveCsvFileLegacy(getCSVData())
        }
    }

    private fun saveCsvFileLegacy(csvContent: String) {
        // Your legacy code for saving CSV to external storage
        // ...

        // Example:
        if (CsvUtils.saveToCsv(this, "${selectedProvider}_${runType}_PHONE.csv", csvContent)) {
            // File saved successfully
            Toast.makeText(this, "CSV file saved successfully", Toast.LENGTH_SHORT).show()
        } else {
            // Failed to save file
            Toast.makeText(this, "Failed to save CSV file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getCSVData(): String {
        val csvString = builder.toString()
        builder.clear()
        return csvString
    }


    private fun keepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun sendEndMessages(serverAddress: InetAddress) {

        GlobalScope.launch(Dispatchers.Unconfined) {
            for(i in 0..60) {
                val destinationPort = Random.nextInt(1024, 49151)
                sendUdpPacket(
                    "END OF MESSAGE",
                    serverAddress,
                    destinationPort,
                    -1,
                )
                delay(150)
            }
        }
    }

    private fun getRunConfigs(): RunConfig {
        val runConfig: RunConfig = if(runType == RunType.RAPID){
            RunConfig(20, 20, 300000)
        }else {
            val list = listOf(380L, 400L, 420L, 440L, 450L, 460L, 480L, 500L, 550L)
            val randomIndex = Random.nextInt(list.size)
            RunConfig(1, list[randomIndex] , 0)
        }
        println("$runConfig")
        return runConfig
    }
}


data class RunConfig(val numberOfRuns: Int, val delayBetweenTransmissions: Long, val delayBetweenRuns: Long)