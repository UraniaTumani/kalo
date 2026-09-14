import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Star } from 'lucide-react'
import { rideApi } from '@/lib/api/endpoints'
import { ApiError } from '@/lib/api/client'
import { Alert, Button, Card, CardBody, CardHeader, Field, Textarea } from '@/components/ui'
import { ErrorMessage } from '@/components/ErrorMessage'
import { cn } from '@/lib/utils'

export function RatingForm({ rideId }: { rideId: number }) {
  const { t } = useTranslation()
  const [driverRating, setDriverRating] = useState(0)
  const [companyRating, setCompanyRating] = useState(0)
  const [comment, setComment] = useState('')

  const queryClient = useQueryClient()

  const mutation = useMutation({
    mutationFn: () =>
      rideApi.rate(rideId, {
        driverRating,
        companyRating,
        comment: comment.trim() || null,
      }),
    /*
     * History carries the rating now, so the row this was opened from has to
     * be re-read — otherwise it keeps offering a Rate button until the next
     * reload, which is the same stale state this whole change is about.
     */
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ride'] }),
  })

  /*
   * One rating per ride, enforced by a unique constraint, so a 409 means this
   * ride was already rated — by an earlier visit, or another tab.
   *
   * It used to be shown as "Your rating has been recorded", which was untrue:
   * the submission was refused and the stored rating is somebody's earlier
   * one. Saying so plainly costs nothing and stops the screen claiming credit
   * for work it did not do.
   */
  const alreadyRated = mutation.error instanceof ApiError && mutation.error.status === 409

  if (alreadyRated) {
    return (
      <Card>
        <CardBody>
          <Alert tone="info" title={t('rating.alreadyRatedTitle')}>
            {t('rating.alreadyRatedBody')}
          </Alert>
        </CardBody>
      </Card>
    )
  }

  if (mutation.isSuccess) {
    return (
      <Card>
        <CardBody>
          <Alert tone="success" title={t('rating.thanks')}>
            {t('rating.recorded')}
          </Alert>
        </CardBody>
      </Card>
    )
  }

  const canSubmit = driverRating > 0 && companyRating > 0

  return (
    <Card>
      <CardHeader title={t('rating.title')} description={t('rating.subtitle')} />
      <CardBody className="space-y-4">
        {mutation.error && !alreadyRated && <ErrorMessage error={mutation.error} />}

        <Stars label={t('rating.driver')} value={driverRating} onChange={setDriverRating} />
        <Stars label={t('rating.company')} value={companyRating} onChange={setCompanyRating} />

        <Field label={t('rating.comment')}>
          <Textarea
            rows={3}
            maxLength={1000}
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            placeholder={t('rating.commentPlaceholder')}
          />
        </Field>

        <Button
          className="w-full"
          disabled={!canSubmit}
          loading={mutation.isPending}
          onClick={() => mutation.mutate()}
        >
          {t('rating.submit')}
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
