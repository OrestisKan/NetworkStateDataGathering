import kotlinx.coroutines.*
import sun.nio.ch.NativeThread.signal
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.time.LocalDateTime
import java.util.concurrent.Executors
import kotlin.system.exitProcess


@Volatile
var endOfTransmissionReceived: Boolean = false
var globalString = "Message id, Time, Sender Port, Receiver Port\n"
var jobList = mutableListOf<Deferred<Unit>>()



fun end() {
    println("Ending signal received!")

    val fileName = "data_${LocalDateTime.now()}.csv"
    File(fileName).writeText(globalString)
    println("File is done")
    for (job in jobList) {
        try {
            job.cancel()
        }catch (e: Exception) {
            e.printStackTrace()
        }
    }
    exitProcess(0)
}

fun launchSockets(index: Int) {
    val bufferSize = 36
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
            val stringData = String(data).trim().replace("\n", "")

//            if(!stringData.matches("\\A\\p{ASCII}*\\z\n")) continue

            if (stringData.contains("END OF MESSAGE") && !endOfTransmissionReceived) {
                endOfTransmissionReceived = true
                end()
                return
            }

            val message = "${stringData}, ${System.currentTimeMillis()}, $senderPort, ${socket.localPort}\n"
            globalString += message

            println(message)
        }
    } catch (e: Exception) {
        println("Failed at index $index with error ${e.printStackTrace()}")
        globalString += "Failed, to, launch, $index\n"
    }

}

fun signalHandler(signum: Int) {
    // your logic here when interrupt happens
}



suspend fun main(args: Array<String>) {
//    signal(SIGINT, signalHandler)
    var ipAddress = "0.0.0.0"
    var numberOfPorts = 65534
    try {
        val one = args[0]
        numberOfPorts = one.toInt()
        val two = args[1]
        ipAddress = two
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