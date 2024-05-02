package airline.api

interface PassengerNotificationEvents {
    val flight: Flight
}

data class DelayFlightPassengerEmail(override val flight: Flight) : PassengerNotificationEvents

data class CancelFlightPassengerEmail(override val flight: Flight) : PassengerNotificationEvents

data class CheckInNumberPassengerEmail(override val flight: Flight) : PassengerNotificationEvents

data class GateNumberPassengerEmail(override val flight: Flight) : PassengerNotificationEvents
