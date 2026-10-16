import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, Check } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { useAuth } from '@/auth/AuthContext'
import { PageHeader } from '@/components/AppLayout'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Pagination } from '@/components/Pagination'
import { Button, Card, EmptyState } from '@/components/ui'
import { Spinner } from '@/components/ui/Spinner'
import { partnerApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import { formatDateTime, cn } from '@/lib/utils'
import { partnerNotificationKeys } from './notificationQueries'
import { PARTNER_LIVE_REFRESH_MS } from './liveRefresh'

/**
 * F1: the backend already creates and scopes RIDE_RATED notifications.
 * This UI only reads the existing inbox and explicitly marks items read.
 */
export function PartnerNotificationsPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const userId = user?.id ?? 0
  const [page, setPage] = useState(0)

  const notificationsQuery = useQuery({
    queryKey: partnerNotificationKeys.list(userId, page),
    queryFn: () => partnerApi.notifications({ page, size: 20 }),
    enabled: Boolean(user),
    refetchInterval: PARTNER_LIVE_REFRESH_MS,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: 'always',
    staleTime: 0,
  })

  const markRead = useMutation({
    mutationFn: partnerApi.markNotificationRead,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: partnerNotificationKeys.root(userId) })
    },
  })

  const { rows, page: pageData, isEmpty } = readPage(notificationsQuery.data)

  return (
    <>
      <PageHeader title={t('notifications.title')} description={t('notifications.subtitle')} />
      {notificationsQuery.error && <ErrorMessage error={notificationsQuery.error} />}
      {markRead.error && <ErrorMessage error={markRead.error} />}

      {notificationsQuery.isPending ? (
        <Spinner />
      ) : notificationsQuery.isError ? null : (
        <Card>
          {isEmpty ? (
            <EmptyState
              title={t('notifications.empty')}
              description={t('notifications.emptyHint')}
              icon={<Bell className="size-5" aria-hidden />}
            />
          ) : (
            <ul className="divide-y divide-ink-100" aria-label={t('notifications.title')}>
              {rows.map((notification) => (
                <li
                  key={notification.id}
                  data-testid={`partner-notification-${notification.id}`}
                  className={cn(
                    'flex flex-col gap-3 px-5 py-4 sm:flex-row sm:items-start sm:justify-between',
                    !notification.read && 'bg-brand-50/40',
                  )}
                >
                  <div className="min-w-0 space-y-1.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <p className="text-sm font-semibold text-ink-900">
                        {notification.type === 'RIDE_REQUESTED'
                          ? t('notifications.booking')
                          : t('notifications.rating')}
                        {notification.rideId !== null && (
                          <span className="font-normal text-ink-500">
                            {' · '}{t('notifications.ride', { id: notification.rideId })}
                          </span>
                        )}
                      </p>
                      <span className={cn(
                        'rounded-full px-2 py-0.5 text-[11px] font-semibold',
                        notification.read
                          ? 'bg-ink-100 text-ink-500'
                          : 'bg-brand-100 text-ink-900',
                      )}>
                        {notification.read ? t('notifications.read') : t('notifications.unread')}
                      </span>
                    </div>
                    {/* The existing DTO has an English rendered message rather than
                        structured ratings. Show it unchanged until the API evolves. */}
                    <p className="break-words text-sm text-ink-700">
                      {notification.type === 'RIDE_REQUESTED'
                        ? t('notifications.bookingMessage')
                        : notification.message}
                    </p>
                    {notification.type === 'RIDE_REQUESTED' && (
                      <Link
                        to="/partner/rides"
                        className="inline-flex rounded-lg text-sm font-semibold text-ink-900 underline decoration-brand-500 underline-offset-4 focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand-600"
                      >
                        {t('notifications.viewRides')}
                      </Link>
                    )}
                    <time className="block text-xs text-ink-500" dateTime={notification.createdAt}>
                      {formatDateTime(notification.createdAt)}
                    </time>
                  </div>
                  {!notification.read && (
                    <Button
                      size="sm"
                      variant="secondary"
                      className="shrink-0 self-start"
                      loading={markRead.isPending && markRead.variables === notification.id}
                      disabled={markRead.isPending}
                      onClick={() => markRead.mutate(notification.id)}
                    >
                      <Check className="size-3.5" aria-hidden />
                      {t('notifications.markRead')}
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          )}
          <Pagination page={pageData} onPageChange={setPage} />
        </Card>
      )}
    </>
  )
}
