/**
 * Mirrors the backend DTOs in com.kalo.*.dto.
 *
 * BigDecimal is serialised as a JSON number but read as `number` here;
 * Instant and LocalDate/LocalTime arrive as ISO strings.
 */

export type UserRole = 'CUSTOMER' | 'PARTNER' | 'ADMIN'
export type UserStatus = 'ACTIVE' | 'PENDING' | 'SUSPENDED' | 'DISABLED'

export type VerificationStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED'
export type CompanyStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED'
export type PaymentMethod = 'CASH' | 'CARD_IN_CAR'

export type DriverStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED'
export type DriverAvailabilityStatus = 'OFFLINE' | 'ONLINE' | 'BUSY'

export type VehicleStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED'
export type VehicleType = 'STANDARD' | 'PREMIUM' | 'ELECTRIC' | 'VAN'

export type RideStatus =
  | 'REQUESTED'
  | 'DRIVER_ASSIGNED'
  | 'DRIVER_ARRIVING'
  | 'DRIVER_ARRIVED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'DECLINED'
  | 'CANCELLED'
  | 'NO_RESPONSE'

export type RideRequestStatus = 'SEARCHING' | 'SELECTED' | 'EXPIRED' | 'CANCELLED'

export type DocumentType =
  | 'BUSINESS_REGISTRATION'
  | 'TAXI_LICENSE'
  | 'DRIVING_LICENSE'
  | 'PROFESSIONAL_CERTIFICATE'
  | 'VEHICLE_REGISTRATION'
  | 'INSURANCE'
  | 'PASSENGER_INSURANCE'
  | 'TECHNICAL_INSPECTION'
  | 'TAXIMETER_VERIFICATION'

export type DocumentVerificationStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED'

export type DayOfWeek =
  | 'MONDAY'
  | 'TUESDAY'
  | 'WEDNESDAY'
  | 'THURSDAY'
  | 'FRIDAY'
  | 'SATURDAY'
  | 'SUNDAY'

/** Non-terminal statuses. Mirrors RideStatus.ACTIVE_STATUSES on the backend. */
export const ACTIVE_RIDE_STATUSES: RideStatus[] = [
  'REQUESTED',
  'DRIVER_ASSIGNED',
  'DRIVER_ARRIVING',
  'DRIVER_ARRIVED',
  'IN_PROGRESS',
]

export const isActiveRide = (status: RideStatus) => ACTIVE_RIDE_STATUSES.includes(status)

/* ------------------------------------------------------------------ auth */

export interface LoginResponse {
  accessToken: string
  /** Rotated on every use: store the new one and discard the old. */
  refreshToken: string
  tokenType: string
  /** Access token lifetime in seconds. */
  expiresIn: number
}

export interface UserResponse {
  id: number
  firstName: string
  lastName: string
  phone: string
  email: string | null
  role: UserRole
  status: UserStatus
  phoneVerified: boolean
}

export interface PartnerRegisterResponse {
  userId: number
  companyId: number
  firstName: string
  lastName: string
  phone: string
  legalName: string
  displayName: string
  nipt: string
  userStatus: UserStatus
  verificationStatus: VerificationStatus
  companyStatus: CompanyStatus
}

/* -------------------------------------------------------------- customer */

export interface TaxiOptionResponse {
  offerId: number
  companyId: number
  companyName: string
  companyRating: number | null
  companyRatingCount: number | null
  distanceKm: number
  nearestDriverId: number
  driverRating: number | null
  driverRatingCount: number | null
  vehicleId: number
  plateNumber: string
  vehicleBrand: string
  vehicleModel: string
  vehicleType: VehicleType
  paymentMethods: PaymentMethod[]
  pricingNote: string
}

/**
 * What an anonymous visitor gets back. Narrower than TaxiOptionResponse on
 * purpose: no offer id (nothing is persisted, so nothing is selectable) and no
 * driver or vehicle identity.
 */
export interface GuestTaxiOptionResponse {
  companyId: number
  companyName: string
  companyRating: number | null
  companyRatingCount: number | null
  distanceKm: number
  vehicleType: VehicleType
  paymentMethods: PaymentMethod[]
  pricingNote: string
}

export interface GuestAvailabilityResponse {
  checkedAt: string
  companiesAvailable: number
  taxiOptions: GuestTaxiOptionResponse[]
  note: string
}

export interface RideSearchResponse {
  rideRequestId: number
  status: RideRequestStatus
  expiresAt: string
  taxiOptions: TaxiOptionResponse[]
}

export interface RideResponse {
  rideId: number
  rideRequestId: number
  companyId: number
  companyName: string
  driverId: number | null
  vehicleId: number | null
  status: RideStatus
  pickupAddress: string | null
  destinationAddress: string | null
  requestedAt: string
  acceptedAt: string | null
  driverArrivingAt: string | null
  driverArrivedAt: string | null
  startedAt: string | null
  completedAt: string | null
  finalAmount: number | null
}

export interface RideRatingResponse {
  ratingId: number
  rideId: number
  driverId: number
  driverName: string
  driverRating: number
  companyId: number
  companyName: string
  companyRating: number
  comment: string | null
  createdAt: string
}

/* --------------------------------------------------------------- partner */

export interface PartnerRideResponse {
  rideId: number
  customerFirstName: string
  customerLastName: string
  customerPhone: string
  pickupLatitude: number
  pickupLongitude: number
  pickupAddress: string | null
  destinationLatitude: number
  destinationLongitude: number
  destinationAddress: string | null
  status: RideStatus
  requestedAt: string
}

export interface PartnerProfileResponse {
  companyId: number
  legalName: string
  displayName: string
  nipt: string
  phone: string
  email: string | null
  address: string
  licenseNumber: string | null
  licenseExpiryDate: string | null
  verificationStatus: VerificationStatus
  status: CompanyStatus
}

export interface PartnerVerificationResponse {
  companyId: number
  verificationStatus: VerificationStatus
  message: string
}

export interface OperationalSettingsResponse {
  companyId: number
  companyName: string
  bookingEnabled: boolean
  paymentMethods: PaymentMethod[]
}

export interface ServiceAreaResponse {
  companyId: number
  latitude: number | null
  longitude: number | null
  radiusKm: number | null
  timezone: string
}

export interface OperatingHoursResponse {
  dayOfWeek: DayOfWeek
  openTime: string | null
  closeTime: string | null
  closed: boolean
}

export interface DriverResponse {
  id: number
  firstName: string
  lastName: string
  phone: string
  licenseNumber: string
  licenseExpiryDate: string
  dateOfBirth: string | null
  rating: number | null
  status: DriverStatus
  availabilityStatus: DriverAvailabilityStatus
}

export interface VehicleResponse {
  id: number
  plateNumber: string
  brand: string
  model: string
  manufactureYear: number
  seats: number
  vehicleType: VehicleType
  registrationExpiryDate: string | null
  insuranceExpiryDate: string | null
  technicalInspectionExpiryDate: string | null
  status: VehicleStatus
}

export interface DriverVehicleAssignmentResponse {
  assignmentId: number
  driverId: number
  driverName: string
  vehicleId: number
  plateNumber: string
  vehicleDescription: string
  assignedFrom: string
  assignedUntil: string | null
  active: boolean
}

export interface DriverLocationResponse {
  driverId: number
  latitude: number
  longitude: number
  locationUpdatedAt: string
}

export interface DocumentResponse {
  id: number
  documentType: DocumentType
  documentNumber: string | null
  fileUrl: string
  issuedAt: string | null
  expiresAt: string | null
  verificationStatus: DocumentVerificationStatus
  rejectionReason: string | null
}

/* ----------------------------------------------------------------- admin */

export interface AdminPartnerResponse {
  companyId: number
  legalName: string
  displayName: string
  nipt: string
  phone: string
  email: string | null
  verificationStatus: VerificationStatus
  companyStatus: CompanyStatus
}

export interface AdminDocumentResponse extends DocumentResponse {}

export interface AdminPartnerDetailResponse {
  companyId: number
  ownerUserId: number
  ownerFirstName: string
  ownerLastName: string
  legalName: string
  displayName: string
  nipt: string
  phone: string
  email: string | null
  address: string
  licenseNumber: string | null
  licenseExpiryDate: string | null
  verificationStatus: VerificationStatus
  companyStatus: CompanyStatus
  ownerStatus: UserStatus
  documents: AdminDocumentResponse[]
}

export interface PartnerDecisionResponse {
  companyId: number
  verificationStatus: VerificationStatus
  companyStatus: CompanyStatus
  message: string
}

export interface AdminUserResponse {
  userId: number
  firstName: string
  lastName: string
  phone: string
  email: string | null
  role: UserRole
  status: UserStatus
  phoneVerified: boolean
  createdAt: string
}

export interface AdminRideResponse {
  rideId: number
  customerId: number
  customerName: string
  companyId: number
  companyName: string
  driverId: number | null
  driverName: string | null
  status: RideStatus
  requestedAt: string
  completedAt: string | null
  finalAmount: number | null
}

export interface AdminRideDetailResponse {
  rideId: number
  rideRequestId: number
  customerId: number
  customerName: string
  customerPhone: string
  companyId: number
  companyName: string
  driverId: number | null
  driverName: string | null
  vehicleId: number | null
  vehiclePlateNumber: string | null
  status: RideStatus
  pickupLatitude: number
  pickupLongitude: number
  pickupAddress: string | null
  destinationLatitude: number
  destinationLongitude: number
  destinationAddress: string | null
  requestedAt: string
  acceptedAt: string | null
  declinedAt: string | null
  cancelledAt: string | null
  driverArrivingAt: string | null
  driverArrivedAt: string | null
  startedAt: string | null
  completedAt: string | null
  finalAmount: number | null
}

/* ------------------------------------------------------------ pagination */

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
  first: boolean
  last: boolean
  empty: boolean
}

/** The shared error body produced by GlobalExceptionHandler. */
export interface ErrorResponse {
  status: number
  error: string
  message: string
  path: string
  timestamp: string
}
