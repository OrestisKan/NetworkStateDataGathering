package nl.tudelft.datagathererapp

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
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
import java.util.UUID
import kotlin.random.Random


class MainActivity : AppCompatActivity() {

    private lateinit var ipEditText: EditText
    private lateinit var sendPacketsButton: Button
    private lateinit var downloadButton: Button
    private lateinit var stopButton: Button
    private lateinit var deferredRun: Deferred<Unit>
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
            deferredRun = GlobalScope.async {
                sendAllPackets(InetAddress.getByName(ipEditText.text.toString()))
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
        builder.append("Message id, Time, Sender Port, Receiver Port, Success").appendLine()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun sendAllPackets(serverAddress: InetAddress) {
        var counter = 0
        var failures: Int
        while (counter < 20) {
            failures = 0
            val sourcePortsSet = (0..65535).toList().shuffled()
            val destinationPortsSet = (0..65535).toList().shuffled()

            val zippedList = sourcePortsSet.zip(destinationPortsSet)
//            val finalList = listOf<Pair<Int,Int>>()
//            for(i in 0..2000)
//                finalList.add
            var index = 1
            val newZippedList = zippedList.take(1000) // todo remove this!!!!!!!!!!!
            for (pair in newZippedList) {
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
                delay(20)
                index++
            }
            println("Number of failures: $failures")
            counter++
            println("---------------------------")
            println("Run $counter / 20 finished!!!!")
            println("---------------------------")
            delay(300000) // wait 5 mins, makes sure that maps are clossing
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
            val sendData = messageBody.toByteArray(Charsets.UTF_8)

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
            // Use Storage Access Framework for Android 10 and above
            saveCsvLauncher.launch("data_${LocalDateTime.now()}.csv")
        } else {
            // For older versions, you can use the previous method
            saveCsvFileLegacy(getCSVData())
        }
    }

    private fun saveCsvFileLegacy(csvContent: String) {
        // Your legacy code for saving CSV to external storage
        // ...

        // Example:
        if (CsvUtils.saveToCsv(this, "data.csv", csvContent)) {
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
}
