import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Star } from 'lucide-react'
import { rideApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import { Alert, Button, Card, CardBody, CardHeader, Field, Textarea } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { cn } from '@/lib/utils'

export function RatingForm({ rideId }: { rideId: number }) {
  const [driverRating, setDriverRating] = useState(0)
  const [companyRating, setCompanyRating] = useState(0)
  const [comment, setComment] = useState('')

  const mutation = useMutation({
    mutationFn: () =>
      rideApi.rate(rideId, {
        driverRating,
        companyRating,
        comment: comment.trim() || null,
      }),
  })

  // The backend enforces one rating per ride with a unique constraint, so a
  // 409 here means it was already rated — not a failure worth alarming about.
  const alreadyRated = mutation.error instanceof ApiError && mutation.error.status === 409

  if (mutation.isSuccess || alreadyRated) {
    return (
      <Card>
        <CardBody>
          <Alert tone="success" title="Thanks for the feedback">
            Your rating has been recorded.
          </Alert>
        </CardBody>
      </Card>
    )
  }

  const canSubmit = driverRating > 0 && companyRating > 0

  return (
    <Card>
      <CardHeader title="Rate this ride" description="Help other passengers choose." />
      <CardBody className="space-y-4">
        {mutation.error && !alreadyRated && <ErrorMessage error={mutation.error} />}

        <Stars label="Driver" value={driverRating} onChange={setDriverRating} />
        <Stars label="Company" value={companyRating} onChange={setCompanyRating} />

        <Field label="Comment (optional)">
          <Textarea
            rows={3}
            maxLength={1000}
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            placeholder="Anything worth mentioning?"
          />
        </Field>

        <Button
          className="w-full"
          disabled={!canSubmit}
          loading={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          Submit rating
        </Button>
      </CardBody>
    </Card>
  )
}

function Stars({
  label,
  value,
  onChange,
}: {
  label: string
  value: number
  onChange: (next: number) => void
}) {
  return (
    <div>
      <p className="mb-1.5 text-xs font-medium text-ink-700">{label}</p>
      <div className="flex gap-1" role="radiogroup" aria-label={`${label} rating`}>
        {[1, 2, 3, 4, 5].map((score) => (
          <button
            key={score}
            type="button"
            role="radio"
            aria-checked={value === score}
            aria-label={`${score} of 5`}
            onClick={() => onChange(score)}
            className="rounded p-0.5 focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-500"
          >
            <Star
              className={cn(
                'size-6 transition',
                score <= value ? 'fill-amber-400 text-amber-400' : 'text-ink-300',
              )}
              aria-hidden
            />
          </button>
        ))}
      </div>
    </div>
  )
}
