package airline.service

import airline.api.PassengerNotificationEvents

interface PassengerNotificationService {
    suspend fun send(emailEvent: PassengerNotificationEvents)
}
