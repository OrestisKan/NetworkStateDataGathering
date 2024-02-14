import kotlinx.coroutines.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.system.exitProcess


@Volatile
var endOfTransmissionReceived: Boolean = false
var jobList = mutableListOf<Deferred<Unit>>()
val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm")

val initialString = "Message id, Time, Sender Port, Receiver Port\n"

@Volatile
var providersHashMap: HashMap<String, String> = HashMap()



fun end() {
    println("Ending signal received!")

    for (job in jobList) {
        try {
            job.cancel()
        }catch (e: Exception) {
            e.printStackTrace()
        }
    }

    println("Closed all ports")
    val keys = providersHashMap.keys.toList()
    for (key in keys){
        println("Generating file for $key")
        endProvider(key)
    }
}

fun endProvider(providerName: String) {
    println("Entered End Provider")
    if (!providersHashMap.contains(providerName)) return
    println("Starting file generation for $providerName...")
    val fileName = "${providerName}_SERVER_${formatter.format(LocalDateTime.now())}.csv"
    val providerResults = providersHashMap.remove(providerName)!!
    File(fileName).writeText(providerResults)
}

fun launchSockets(index: Int) {
    val bufferSize = 100 //35 for UUID and the rest to account for various providers names
    try {
        val socket = DatagramSocket(index)
        while (true) {
            if (endOfTransmissionReceived) return

            val buffer = ByteArray(bufferSize)


            println("UDP server is listening on port ${socket.localPort} and address ${socket.localAddress} with index $index")
            val packet = DatagramPacket(buffer, buffer.size)

            socket.receive(packet)

            val data = packet.data.copyOfRange(0, packet.length)
            val senderPort = packet.port

            println("Received ${String(data)}")

            val dataTemp = String(data)
            val stringDataArray = dataTemp.split("\\n")
            if(stringDataArray.size < 2) continue //means that either provider or data is missing.

            val providerName = stringDataArray[0]
            val stringData = stringDataArray[1].trim().replace("\n", "")

            if (stringData.contains("END OF MESSAGE")) {
                endProvider(providerName)
            }

            val message = "${stringData}, ${System.currentTimeMillis()}, $senderPort, ${socket.localPort}\n"
            var stringToAppend = providersHashMap.getOrDefault(providerName, initialString)
            stringToAppend += message
            providersHashMap.put(providerName, stringToAppend)

            println(message)
        }
    } catch (e: Exception) {
        println("Failed at index $index with error ${e.printStackTrace()}")
    }

}

suspend fun main(args: Array<String>) {
    Runtime.getRuntime().addShutdownHook(Thread({ end() }))
    var numberOfPorts = 65534
    try {
        val one = args[0]
        numberOfPorts = one.toInt()
    } catch (e: ArrayIndexOutOfBoundsException) {
        println("ArrayIndexOutOfBoundsException caught")
    }
        for (i in 1..numberOfPorts) {
            val singleThread = Executors.newSingleThreadExecutor {
                    task -> Thread(task, "thread-$i")
                }.asCoroutineDispatcher()
                val job = GlobalScope.async(singleThread) {launchSockets(i)}
                job.start()
            jobList.add(job)
    }

    jobList.awaitAll()
}