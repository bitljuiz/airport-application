package airline.api

interface BookingEmailEvents {
    val bookingData: BuyTicketMsg
}

data class FailedToBuyTicketMsg(override val bookingData: BuyTicketMsg) : BookingEmailEvents

data class SuccessToBuyTicketMsg(override val bookingData: BuyTicketMsg) : BookingEmailEvents

data class NoSeatMessageMsg(override val bookingData: BuyTicketMsg) : BookingEmailEvents
