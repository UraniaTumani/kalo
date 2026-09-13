import { api } from './client'
import type {
  AdminPartnerDetailResponse,
  AdminPartnerResponse,
  AdminRideDetailResponse,
  AdminRideResponse,
  AdminUserResponse,
  CompanyStatus,
  DocumentResponse,
  DocumentType,
  DriverAvailabilityStatus,
  DriverLocationResponse,
  DriverResponse,
  DriverStatus,
  DriverVehicleAssignmentResponse,
  GuestAvailabilityResponse,
  LoginResponse,
  OperatingHoursResponse,
  OperationalSettingsResponse,
  Page,
  PartnerDecisionResponse,
  PartnerProfileResponse,
  PartnerRegisterResponse,
  PartnerRideResponse,
  PartnerVerificationResponse,
  PaymentMethod,
  RideRatingResponse,
  RideResponse,
  RideSearchResponse,
  RideStatus,
  ServiceAreaResponse,
  UserResponse,
  UserRole,
  UserStatus,
  VehicleResponse,
  VehicleStatus,
  VehicleType,
} from './types'

interface PageParams {
  page?: number
  size?: number
  sort?: string
}

/* ------------------------------------------------------------------ auth */

export const authApi = {
  login: (body: { phone: string; password: string }) =>
    api.post<LoginResponse>('/api/v1/auth/login', body, { skipAuthRedirect: true }),

  registerCustomer: (body: {
    firstName: string
    lastName: string
    phone: string
    email?: string | null
    password: string
  }) =>
    api.post<UserResponse>('/api/v1/auth/register/customer', body, {
      skipAuthRedirect: true,
    }),

  registerPartner: (body: {
    firstName: string
    lastName: string
    phone: string
    email?: string | null
    password: string
    legalName: string
    displayName: string
    nipt: string
    address: string
  }) =>
    api.post<PartnerRegisterResponse>('/api/v1/auth/register/partner', body, {
      skipAuthRedirect: true,
    }),

  /**
   * Refreshing itself lives in client.ts, not here: it has to bypass the
   * retry wrapper that would otherwise call it again.
   */
  logout: (refreshToken: string) =>
    api.post<void>('/api/v1/auth/logout', { refreshToken }, { skipAuthRedirect: true }),

  me: () => api.get<UserResponse>('/api/v1/me'),

  updateMe: (body: { firstName: string; lastName: string; email?: string | null }) =>
    api.put<UserResponse>('/api/v1/me', body),
}

/* ----------------------------------------------------------------- guest */

export const publicApi = {
  /**
   * Anonymous, read-only. Creates no ride request and no offers, so there is
   * nothing here to select — booking needs an account.
   */
  taxiAvailability: (body: { latitude: number; longitude: number }) =>
    api.post<GuestAvailabilityResponse>('/api/v1/public/taxi-availability', body, {
      skipAuthRedirect: true,
    }),
}

/* -------------------------------------------------------------- customer */

export const rideApi = {
  search: (body: {
    pickupLatitude: number
    pickupLongitude: number
    pickupAddress?: string | null
    destinationLatitude: number
    destinationLongitude: number
    destinationAddress?: string | null
  }) => api.post<RideSearchResponse>('/api/v1/rides/search', body),

  selectOffer: (rideRequestId: number, offerId: number) =>
    api.post<RideResponse>(`/api/v1/rides/requests/${rideRequestId}/select`, { offerId }),

  current: () => api.get<RideResponse>('/api/v1/rides/current'),

  byId: (rideId: number) => api.get<RideResponse>(`/api/v1/rides/${rideId}`),

  history: (params?: PageParams) => api.get<Page<RideResponse>>('/api/v1/rides/history', params),

  cancel: (rideId: number) => api.post<RideResponse>(`/api/v1/rides/${rideId}/cancel`),

  rate: (
    rideId: number,
    body: { driverRating: number; companyRating: number; comment?: string | null },
  ) => api.post<RideRatingResponse>(`/api/v1/rides/${rideId}/rating`, body),
}

/* --------------------------------------------------------------- partner */

export const partnerApi = {
  profile: () => api.get<PartnerProfileResponse>('/api/v1/partner/me'),

  updateProfile: (body: {
    legalName: string
    displayName: string
    phone: string
    email?: string | null
    address: string
    licenseNumber?: string | null
    licenseExpiryDate?: string | null
  }) => api.put<PartnerProfileResponse>('/api/v1/partner/me', body),

  submitVerification: () =>
    api.post<PartnerVerificationResponse>('/api/v1/partner/submit-verification'),

  documents: () => api.get<DocumentResponse[]>('/api/v1/partner/documents'),

  addDocument: (body: {
    documentType: DocumentType
    documentNumber?: string | null
    fileUrl: string
    issuedAt?: string | null
    expiresAt?: string | null
  }) => api.post<DocumentResponse>('/api/v1/partner/documents', body),

  deleteDocument: (documentId: number) =>
    api.delete<void>(`/api/v1/partner/documents/${documentId}`),

  operationalSettings: () =>
    api.get<OperationalSettingsResponse>('/api/v1/partner/operational-settings'),

  updateOperationalSettings: (body: {
    bookingEnabled: boolean
    paymentMethods: PaymentMethod[]
  }) => api.put<OperationalSettingsResponse>('/api/v1/partner/operational-settings', body),

  serviceArea: () =>
    api.get<ServiceAreaResponse>('/api/v1/partner/availability-settings/service-area'),

  updateServiceArea: (body: {
    latitude: number
    longitude: number
    radiusKm: number
    timezone: string
  }) =>
    api.put<ServiceAreaResponse>('/api/v1/partner/availability-settings/service-area', body),

  operatingHours: () =>
    api.get<OperatingHoursResponse[]>('/api/v1/partner/availability-settings/operating-hours'),

  updateOperatingHours: (hours: OperatingHoursResponse[]) =>
    api.put<OperatingHoursResponse[]>(
      '/api/v1/partner/availability-settings/operating-hours',
      { hours },
    ),

  /* fleet */

  /**
   * Paged. The pickers pass availabilityStatus/status rather than reading a
   * whole fleet and filtering in the browser.
   */
  drivers: (
    params?: PageParams & {
      status?: DriverStatus
      availabilityStatus?: DriverAvailabilityStatus
      unassigned?: boolean
    },
  ) => api.get<Page<DriverResponse>>('/api/v1/partner/drivers', params),

  driver: (driverId: number) => api.get<DriverResponse>(`/api/v1/partner/drivers/${driverId}`),

  createDriver: (body: {
    firstName: string
    lastName: string
    phone: string
    licenseNumber: string
    licenseExpiryDate: string
    dateOfBirth?: string | null
  }) => api.post<DriverResponse>('/api/v1/partner/drivers', body),

  updateDriver: (
    driverId: number,
    body: {
      firstName: string
      lastName: string
      phone: string
      licenseNumber: string
      licenseExpiryDate: string
      dateOfBirth?: string | null
      status: DriverStatus
    },
  ) => api.put<DriverResponse>(`/api/v1/partner/drivers/${driverId}`, body),

  setAvailability: (driverId: number, availabilityStatus: DriverAvailabilityStatus) =>
    api.patch<DriverResponse>(`/api/v1/partner/drivers/${driverId}/availability`, {
      availabilityStatus,
    }),

  deactivateDriver: (driverId: number) =>
    api.delete<void>(`/api/v1/partner/drivers/${driverId}`),

  driverLocation: (driverId: number) =>
    api.get<DriverLocationResponse>(`/api/v1/partner/drivers/${driverId}/location`),

  updateDriverLocation: (driverId: number, body: { latitude: number; longitude: number }) =>
    api.put<DriverLocationResponse>(`/api/v1/partner/drivers/${driverId}/location`, body),

  vehicles: (params?: PageParams & { status?: VehicleStatus; unassigned?: boolean }) =>
    api.get<Page<VehicleResponse>>('/api/v1/partner/vehicles', params),

  createVehicle: (body: {
    plateNumber: string
    brand: string
    model: string
    manufactureYear: number
    seats: number
    vehicleType: VehicleType
    registrationExpiryDate?: string | null
    insuranceExpiryDate?: string | null
    technicalInspectionExpiryDate?: string | null
  }) => api.post<VehicleResponse>('/api/v1/partner/vehicles', body),

  updateVehicle: (
    vehicleId: number,
    body: {
      plateNumber: string
      brand: string
      model: string
      manufactureYear: number
      seats: number
      vehicleType: VehicleType
      registrationExpiryDate?: string | null
      insuranceExpiryDate?: string | null
      technicalInspectionExpiryDate?: string | null
      status: VehicleStatus
    },
  ) => api.put<VehicleResponse>(`/api/v1/partner/vehicles/${vehicleId}`, body),

  deactivateVehicle: (vehicleId: number) =>
    api.delete<void>(`/api/v1/partner/vehicles/${vehicleId}`),

  assignments: (params?: PageParams & { active?: boolean }) =>
    api.get<Page<DriverVehicleAssignmentResponse>>(
      '/api/v1/partner/driver-vehicle-assignments',
      params,
    ),

  createAssignment: (body: { driverId: number; vehicleId: number }) =>
    api.post<DriverVehicleAssignmentResponse>(
      '/api/v1/partner/driver-vehicle-assignments',
      body,
    ),

  removeAssignment: (assignmentId: number) =>
    api.delete<void>(`/api/v1/partner/driver-vehicle-assignments/${assignmentId}`),

  /* rides */

  rides: (params?: PageParams & { status?: RideStatus }) =>
    api.get<Page<PartnerRideResponse>>('/api/v1/partner/rides', params),

  accept: (rideId: number, driverId: number) =>
    api.post<RideResponse>(`/api/v1/partner/rides/${rideId}/accept`, { driverId }),

  decline: (rideId: number) => api.post<RideResponse>(`/api/v1/partner/rides/${rideId}/decline`),

  driverArriving: (rideId: number) =>
    api.post<RideResponse>(`/api/v1/partner/rides/${rideId}/driver-arriving`),

  driverArrived: (rideId: number) =>
    api.post<RideResponse>(`/api/v1/partner/rides/${rideId}/driver-arrived`),

  start: (rideId: number) => api.post<RideResponse>(`/api/v1/partner/rides/${rideId}/start`),

  complete: (rideId: number, finalAmount: number) =>
    api.post<RideResponse>(`/api/v1/partner/rides/${rideId}/complete`, { finalAmount }),
}

/* ----------------------------------------------------------------- admin */

export const adminApi = {
  partners: (
    params?: PageParams & { status?: string; companyStatus?: CompanyStatus },
  ) => api.get<Page<AdminPartnerResponse>>('/api/v1/admin/partners', params),

  partner: (companyId: number) =>
    api.get<AdminPartnerDetailResponse>(`/api/v1/admin/partners/${companyId}`),

  approvePartner: (companyId: number) =>
    api.post<PartnerDecisionResponse>(`/api/v1/admin/partners/${companyId}/approve`),

  rejectPartner: (companyId: number, reason: string) =>
    api.post<PartnerDecisionResponse>(`/api/v1/admin/partners/${companyId}/reject`, { reason }),

  suspendPartner: (companyId: number) =>
    api.post<PartnerDecisionResponse>(`/api/v1/admin/partners/${companyId}/suspend`),

  reactivatePartner: (companyId: number) =>
    api.post<PartnerDecisionResponse>(`/api/v1/admin/partners/${companyId}/reactivate`),

  users: (params?: PageParams & { role?: UserRole; status?: UserStatus }) =>
    api.get<Page<AdminUserResponse>>('/api/v1/admin/users', params),

  user: (userId: number) => api.get<AdminUserResponse>(`/api/v1/admin/users/${userId}`),

  suspendUser: (userId: number) =>
    api.post<AdminUserResponse>(`/api/v1/admin/users/${userId}/suspend`),

  reactivateUser: (userId: number) =>
    api.post<AdminUserResponse>(`/api/v1/admin/users/${userId}/reactivate`),

  rides: (params?: PageParams & { status?: RideStatus; companyId?: number }) =>
    api.get<Page<AdminRideResponse>>('/api/v1/admin/rides', params),

  ride: (rideId: number) => api.get<AdminRideDetailResponse>(`/api/v1/admin/rides/${rideId}`),
}
