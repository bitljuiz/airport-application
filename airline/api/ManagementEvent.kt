package airline.api

import kotlinx.datetime.Instant

interface ManagementEvent {
    val flightId: String
    val departureTime: Instant
}

sealed class AirlineManagementEvent : ManagementEvent
sealed class BookingManagementEvent : ManagementEvent
data class ScheduleFlightMsg(
    override val flightId: String,
    override val departureTime: Instant,
    val plane: Plane,
) : AirlineManagementEvent()
data class DelayFlightMsg(
    override val flightId: String,
    override val departureTime: Instant,
    val actualDepartureTime: Instant,
) : AirlineManagementEvent()
data class CancelFlightMsg(
    override val flightId: String,
    override val departureTime: Instant,
) : AirlineManagementEvent()
data class SetCheckInNumberMsg(
    override val flightId: String,
    override val departureTime: Instant,
    val checkInNumber: String,
) : AirlineManagementEvent()
data class SetGateNumberMsg(
    override val flightId: String,
    override val departureTime: Instant,
    val gateNumber: String?,
) : AirlineManagementEvent()
data class BuyTicketMsg(
    override val flightId: String,
    override val departureTime: Instant,
    val seatNo: String,
    val passengerId: String,
    val passengerName: String,
    val passengerEmail: String,
) : BookingManagementEvent()
fun BuyTicketMsg.toTicket(): Ticket {
    return Ticket(
        this.flightId,
        this.departureTime,
        this.seatNo,
        this.passengerId,
        this.passengerName,
        this.passengerEmail,
    )
}
