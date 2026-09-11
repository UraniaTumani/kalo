import { useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { LocateFixed, MapPin, Star } from 'lucide-react'
import { useAuth, homePathFor } from '@/auth/AuthContext'
import { publicApi } from '@/lib/api/endpoints'
import { MapView, TIRANA, type LatLng } from '@/components/MapPicker'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Badge, Button, Card, CardBody, CardHeader } from '@/components/ui'
import { formatDistance, formatTime, humanise } from '@/lib/utils'

/**
 * Open to anyone. Shows which companies could serve a point right now, using
 * the read-only public endpoint: nothing is reserved and nothing is booked,
 * which is why every result ends at a sign-up prompt rather than a button.
 */
export function GuestLandingPage() {
  const { user, role, loading } = useAuth()
  const [point, setPoint] = useState<LatLng | null>(null)
  const [locating, setLocating] = useState(false)

  const availability = useMutation({
    mutationFn: (position: LatLng) =>
      publicApi.taxiAvailability({ latitude: position.lat, longitude: position.lng }),
  })

  // Someone already signed in has a real app to be in.
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Spinner />
      </div>
    )
  }

  if (user) {
    return <Navigate to={homePathFor(role)} replace />
  }

  function check(position: LatLng) {
    setPoint(position)
    availability.mutate(position)
  }

  function useMyLocation() {
    if (!navigator.geolocation) return

    setLocating(true)
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setLocating(false)
        check({ lat: position.coords.latitude, lng: position.coords.longitude })
      },
      () => setLocating(false),
      { timeout: 10_000 },
    )
  }

  const result = availability.data

  return (
    <div className="min-h-screen bg-ink-50">
      <header className="border-b border-ink-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-3 sm:px-6">
          <div className="flex items-center gap-2">
            <span className="grid size-8 place-items-center rounded-lg bg-brand-600 text-sm font-bold text-white">
              K
            </span>
            <span className="text-sm font-semibold text-ink-900">KALO</span>
          </div>
          <div className="flex gap-2">
            <Link to="/login">
              <Button variant="secondary" size="sm">
                Sign in
              </Button>
            </Link>
            <Link to="/register">
              <Button size="sm">Create account</Button>
            </Link>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
        <div className="mb-6 max-w-2xl">
          <p className="text-xs font-medium tracking-wide text-brand-700 uppercase">
            Browsing as a guest
          </p>
          <h1 className="mt-1 text-2xl font-semibold text-ink-900">
            See which taxi companies are near you
          </h1>
          <p className="mt-2 text-sm text-ink-600">
            KALO is a marketplace, not a taxi operator. You pick the{' '}
            <strong className="font-medium text-ink-800">company</strong>, they assign one of
            their drivers, and you pay the driver directly — the final price is the real
            taximeter amount.
          </p>
        </div>

        <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
          <Card>
            <CardHeader
              title="Pick a location"
              description="Click anywhere on the map to check that spot."
              action={
                <Button
                  size="sm"
                  variant="secondary"
                  loading={locating}
                  onClick={useMyLocation}
                >
                  <LocateFixed className="size-3.5" aria-hidden />
                  Use my location
                </Button>
              }
            />
            <CardBody className="space-y-3">
              <MapView
                center={point ?? TIRANA}
                onPick={check}
                className="h-80 w-full rounded-lg"
                markers={point ? [{ position: point, label: 'Checking here', tone: 'pickup' }] : []}
              />

              {point && (
                <p className="flex items-center gap-1.5 text-xs text-ink-500">
                  <MapPin className="size-3.5" aria-hidden />
                  {point.lat.toFixed(4)}, {point.lng.toFixed(4)}
                  {result && <span>· checked {formatTime(result.checkedAt)}</span>}
                </p>
              )}
            </CardBody>
          </Card>

          <div className="space-y-4">
            <Card>
              <CardHeader
                title="Available now"
                description={
                  result
                    ? `${result.companiesAvailable} ${
                        result.companiesAvailable === 1 ? 'company' : 'companies'
                      } could pick you up`
                    : 'Choose a point to check.'
                }
              />
              <CardBody className="space-y-3">
                {availability.isPending && <Spinner label="Checking availability" />}

                {availability.error && <ErrorMessage error={availability.error} />}

                {!point && !availability.isPending && (
                  <p className="text-sm text-ink-500">
                    Results show each company&apos;s nearest available driver.
                  </p>
                )}

                {result && result.taxiOptions.length === 0 && (
                  <Alert tone="warning" title="Nothing available here right now">
                    No company has an online driver near this point. Try another spot or check
                    again shortly.
                  </Alert>
                )}

                {result?.taxiOptions.map((option) => (
                  <div
                    key={option.companyId}
                    className="rounded-lg border border-ink-200 p-3"
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <p className="truncate text-sm font-semibold text-ink-900">
                          {option.companyName}
                        </p>
                        <p className="mt-0.5 flex items-center gap-1 text-xs text-ink-500">
                          {option.companyRating ? (
                            <>
                              <Star
                                className="size-3 fill-amber-400 text-amber-400"
                                aria-hidden
                              />
                              {option.companyRating.toFixed(1)}
                              <span className="text-ink-400">
                                ({option.companyRatingCount ?? 0})
                              </span>
                            </>
                          ) : (
                            'Not rated yet'
                          )}
                        </p>
                      </div>
                      <Badge tone="info">{formatDistance(option.distanceKm)} away</Badge>
                    </div>

                    <div className="mt-2 flex flex-wrap gap-1">
                      <Badge>{humanise(option.vehicleType)}</Badge>
                      {option.paymentMethods.map((method) => (
                        <Badge key={method}>{humanise(method)}</Badge>
                      ))}
                    </div>

                    <p className="mt-2 text-xs text-ink-500">{option.pricingNote}</p>
                  </div>
                ))}
              </CardBody>
            </Card>

            {result && result.taxiOptions.length > 0 && (
              <Card>
                <CardBody className="space-y-3">
                  <p className="text-sm text-ink-700">{result.note}</p>
                  <Link to="/register" className="block">
                    <Button className="w-full">Create an account to book</Button>
                  </Link>
                </CardBody>
              </Card>
            )}
          </div>
        </div>

        <div className="mt-8 grid gap-4 sm:grid-cols-3">
          <Step
            number={1}
            title="Search"
            body="Tell KALO where you are and where you are going."
          />
          <Step
            number={2}
            title="Choose a company"
            body="You see one offer per company, with its rating and distance."
          />
          <Step
            number={3}
            title="They assign a driver"
            body="The company sends one of its own drivers. You pay the driver directly."
          />
        </div>
      </main>
    </div>
  )
}

function Step({ number, title, body }: { number: number; title: string; body: string }) {
  return (
    <Card>
      <CardBody>
        <span className="grid size-6 place-items-center rounded-full bg-brand-50 text-xs font-semibold text-brand-700">
          {number}
        </span>
        <p className="mt-2 text-sm font-medium text-ink-900">{title}</p>
        <p className="mt-0.5 text-xs text-ink-500">{body}</p>
      </CardBody>
    </Card>
  )
}
