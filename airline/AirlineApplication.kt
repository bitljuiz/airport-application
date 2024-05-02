package airline

import airline.api.*
import airline.service.AirlineManagementService
import airline.service.BookingService
import airline.service.EmailService
import airline.service.PassengerNotificationService
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class AirlineApplication(private val config: AirlineConfig, private val emailService: EmailService) {
    private var airlineEvents = MutableSharedFlow<ManagementEvent>()
    private var flights = MutableStateFlow<List<Flight>>(emptyList())

    private val passengerEmailChannel = Channel<PassengerNotificationEvents>(capacity = DEFAULT_BUFFER_SIZE)
    private val bookingEmailChannel = Channel<BookingEmailEvents>(capacity = DEFAULT_BUFFER_SIZE)

    val bookingService = object : BookingService {
        override val flightSchedule: List<FlightInfo>
            get() = flights.value.filter {
                !it.isCancelled && it.plane.seats.size > it.tickets.size &&
                    Clock.System.now() < it.actualDepartureTime - config.ticketSaleEndTime
            }.map { it.toFlightInfo() }

        override fun freeSeats(flightId: String, departureTime: Instant): Set<String> = flights.value.firstOrNull {
            it.flightId == flightId && it.departureTime == departureTime
        }?.let { flight ->
            flight.plane.seats.filterNot { seat -> flight.tickets.containsKey(seat) }
        }?.toSet() ?: emptySet()

        override suspend fun buyTicket(
            flightId: String,
            departureTime: Instant,
            seatNo: String,
            passengerId: String,
            passengerName: String,
            passengerEmail: String,
        ) {
            airlineEvents.emit(
                BuyTicketMsg(
                    flightId,
                    departureTime,
                    seatNo,
                    passengerId,
                    passengerName,
                    passengerEmail,
                ),
            )
        }
    }

    val managementService = object : AirlineManagementService {
        override suspend fun scheduleFlight(flightId: String, departureTime: Instant, plane: Plane) {
            airlineEvents.emit(
                ScheduleFlightMsg(
                    flightId,
                    departureTime,
                    plane,
                ),
            )
        }

        override suspend fun delayFlight(flightId: String, departureTime: Instant, actualDepartureTime: Instant) {
            airlineEvents.emit(DelayFlightMsg(flightId, departureTime, actualDepartureTime))
        }

        override suspend fun cancelFlight(flightId: String, departureTime: Instant) {
            airlineEvents.emit(CancelFlightMsg(flightId, departureTime))
        }

        override suspend fun setCheckInNumber(flightId: String, departureTime: Instant, checkInNumber: String) {
            airlineEvents.emit(SetCheckInNumberMsg(flightId, departureTime, checkInNumber))
        }

        override suspend fun setGateNumber(flightId: String, departureTime: Instant, gateNumber: String) {
            airlineEvents.emit(SetGateNumberMsg(flightId, departureTime, gateNumber))
        }
    }

    private val bufferedEmailService = object : EmailService {
        suspend fun run() {
            for (email in bookingEmailChannel) {
                when (email) {
                    is FailedToBuyTicketMsg -> {
                        send(
                            email.bookingData.passengerEmail,
                            "Dear ${email.bookingData.passengerName}. " +
                                "Failed to buy a ticket for a flight" +
                                "${email.bookingData.flightId} with departure time " +
                                "${email.bookingData.departureTime}. Cannot find expected flight.",
                        )
                    }

                    is SuccessToBuyTicketMsg -> {
                        send(
                            email.bookingData.passengerEmail,
                            "Dear ${email.bookingData.passengerName}. You have successfully bought a " +
                                "ticket at flight " + "${email.bookingData.flightId} with departure time" +
                                " ${email.bookingData.departureTime}." + "Your seat has number " +
                                "${email.bookingData.seatNo}. " + "Note that information could change. " +
                                "We will inform you on this email",
                        )
                    }

                    is NoSeatMessageMsg -> {
                        send(
                            email.bookingData.passengerEmail,
                            "Dear ${email.bookingData.passengerName}. Unfortunately, Failed to buy a ticket " +
                                "for a flight" + "${email.bookingData.flightId} with departure time" +
                                " ${email.bookingData.departureTime}" + "for the seat ${email.bookingData.seatNo}",
                        )
                    }
                }
            }
        }

        suspend fun sendEmail(event: BookingEmailEvents) {
            bookingEmailChannel.send(event)
        }

        override suspend fun send(to: String, text: String) = emailService.send(to, text)
    }

    private val passengerNotificationService = object : PassengerNotificationService {
        suspend fun run() {
            for (email in passengerEmailChannel) {
                when (email) {
                    is DelayFlightPassengerEmail -> {
                        delayFlightEmail(email.flight)
                    }

                    is CancelFlightPassengerEmail -> {
                        cancelFlightEmail(email.flight)
                    }

                    is CheckInNumberPassengerEmail -> {
                        checkInNumberEmail(email.flight)
                    }

                    is GateNumberPassengerEmail -> {
                        gateNumberEmail(email.flight)
                    }
                }
            }
        }

        private suspend fun delayFlightEmail(flight: Flight) {
            for (passenger in flight.tickets.values) {
                emailService.send(
                    passenger.passengerEmail,
                    "Dear, ${passenger.passengerName}. " +
                        "Please note that flight ${flight.flightId} has " +
                        "delayed and its departure time changed " +
                        " from ${flight.departureTime} to ${flight.actualDepartureTime}",
                )
            }
        }

        private suspend fun cancelFlightEmail(flight: Flight) {
            for (passenger in flight.tickets.values) {
                emailService.send(
                    passenger.passengerEmail,
                    "Dear, ${passenger.passengerName}. Unfortunately, your flight " +
                        "${flight.flightId} with departure time ${flight.departureTime}" +
                        "is cancelled. Our company apologizes",
                )
            }
        }

        private suspend fun checkInNumberEmail(flight: Flight) {
            for (passenger in flight.tickets.values) {
                emailService.send(
                    passenger.passengerEmail,
                    "Dear, ${passenger.passengerName}. We would like to inform you that your flight " +
                        "${flight.flightId} with departure time ${flight.departureTime} " +
                        "has been issued a check-in number ${flight.checkInNumber}",
                )
            }
        }

        private suspend fun gateNumberEmail(flight: Flight) {
            for (passenger in flight.tickets.values) {
                emailService.send(
                    passenger.passengerEmail,
                    "Dear, ${passenger.passengerName}. We would like to inform you that your flight " +
                        "${flight.flightId} with departure time ${flight.departureTime} " +
                        "has been issued a gate number ${flight.gateNumber}",
                )
            }
        }

        override suspend fun send(emailEvent: PassengerNotificationEvents) {
            passengerEmailChannel.send(emailEvent)
        }
    }

    suspend fun airportInformationDisplay(coroutineScope: CoroutineScope): StateFlow<InformationDisplay> {
        return flights
            .map { list ->
                list.filter { flight ->
                    flight.departureTime > Clock.System.now() &&
                        flight.departureTime <= Clock.System.now() + 1.days
                }.map { it.toFlightInfo() }
            }
            .distinctUntilChanged()
            .map { filteredList -> InformationDisplay(filteredList) }
            .debounce(config.displayUpdateInterval)
            .stateIn(coroutineScope)
    }

    val airportAudioAlerts: Flow<AudioAlerts> = flow {
        while (true) {
            flights.value.forEach { flight ->
                val now = Clock.System.now()

                val regOpens = flight.actualDepartureTime.minus(config.registrationOpeningTime)
                val regCloses = flight.actualDepartureTime.minus(config.registrationClosingTime)
                val boardingOpens = flight.actualDepartureTime.minus(config.boardingOpeningTime)
                val boardingCloses = flight.actualDepartureTime.minus(config.boardingClosingTime)

                if (now in regOpens..regOpens.plus(3.minutes)) {
                    emit(AudioAlerts.RegistrationOpen(flight.flightId, flight.checkInNumber ?: "No data"))
                }
                if (now in regCloses.minus(3.minutes)..regCloses) {
                    emit(AudioAlerts.RegistrationClosing(flight.flightId, flight.checkInNumber ?: "No data"))
                }
                if (now in boardingOpens..boardingOpens.plus(3.minutes)) {
                    emit(AudioAlerts.BoardingOpened(flight.flightId, flight.gateNumber ?: "No data"))
                }
                if (now in boardingCloses.minus(3.minutes)..boardingCloses) {
                    emit(AudioAlerts.BoardingClosing(flight.flightId, flight.gateNumber ?: "No data"))
                }
            }
            delay(config.audioAlertsInterval)
        }
    }

    suspend fun run() {
        fun seekForFlight(event: ManagementEvent): Flight? {
            return flights.value.firstOrNull {
                when (event) {
                    is AirlineManagementEvent -> {
                        it.flightId == event.flightId && it.departureTime == event.departureTime && !it.isCancelled
                    }

                    is BookingManagementEvent -> {
                        it.flightId == event.flightId && it.departureTime == event.departureTime && !it.isCancelled &&
                            Clock.System.now() < event.departureTime - config.ticketSaleEndTime
                    }

                    else -> {
                        false
                    }
                }
            }
        }

        fun changeFlightStatus(flight: Flight, transform: (Flight) -> Flight) {
            val changedFlight = transform(flight)
            flights.value = flights.value + changedFlight - flight
        }

        CoroutineScope(Dispatchers.Default).launch {
            launch {
                airlineEvents.collect { event ->
                    when (event) {
                        is ScheduleFlightMsg -> {
                            flights.value += Flight(
                                flightId = event.flightId,
                                departureTime = event.departureTime,
                                plane = event.plane,
                            )

                            bookingService.flightSchedule.toMutableList() += FlightInfo(
                                flightId = event.flightId,
                                departureTime = event.departureTime,
                                plane = event.plane,
                            )
                        }

                        is DelayFlightMsg -> {
                            val delayedFlight = seekForFlight(event) ?: return@collect

                            changeFlightStatus(delayedFlight) {
                                it.copy(actualDepartureTime = event.actualDepartureTime)
                            }
                            passengerNotificationService.send(DelayFlightPassengerEmail(delayedFlight))
                        }

                        is CancelFlightMsg -> {
                            val cancelledFlight = seekForFlight(event) ?: return@collect

                            changeFlightStatus(cancelledFlight) {
                                it.copy(isCancelled = true)
                            }
                            passengerNotificationService.send(CancelFlightPassengerEmail(cancelledFlight))
                        }

                        is SetCheckInNumberMsg -> {
                            val flightWithCheckIn = seekForFlight(event) ?: return@collect

                            changeFlightStatus(flightWithCheckIn) {
                                it.copy(checkInNumber = event.checkInNumber)
                            }
                            passengerNotificationService.send(
                                CheckInNumberPassengerEmail(
                                    flightWithCheckIn.copy(checkInNumber = event.checkInNumber),
                                ),
                            )
                        }

                        is SetGateNumberMsg -> {
                            val flightWithGateNumber = seekForFlight(event) ?: return@collect

                            changeFlightStatus(flightWithGateNumber) {
                                it.copy(gateNumber = event.gateNumber)
                            }
                            passengerNotificationService.send(
                                GateNumberPassengerEmail(
                                    flightWithGateNumber.copy(gateNumber = event.gateNumber),
                                ),
                            )
                        }

                        is BuyTicketMsg -> {
                            val flight = seekForFlight(event) ?: run {
                                bufferedEmailService.sendEmail(FailedToBuyTicketMsg(event))
                                return@collect
                            }

                            val availableTickets = bookingService.freeSeats(flight.flightId, flight.departureTime)

                            if (availableTickets.isNotEmpty() && availableTickets.contains(event.seatNo)) {
                                flight.tickets[event.seatNo] = event.toTicket()

                                bufferedEmailService.sendEmail(SuccessToBuyTicketMsg(event))
                            } else {
                                bufferedEmailService.sendEmail(NoSeatMessageMsg(event))
                            }
                        }
                    }
                }
            }

            launch {
                bufferedEmailService.run()
            }

            launch {
                passengerNotificationService.run()
            }
        }
    }
}
