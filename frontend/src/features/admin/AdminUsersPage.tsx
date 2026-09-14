import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { AdminUserResponse, UserRole, UserStatus } from '@/lib/api/types'
import { UserX } from 'lucide-react'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Spinner } from '@/components/ui/Spinner'
import { Badge, Button, Card, EmptyState, Field, FilterBar, RowActions, Select, Table, Td, Th } from '@/components/ui'
import { formatDateTime } from '@/lib/utils'
import { useIsCompact } from '@/lib/useIsCompact'

const ROLES: (UserRole | 'ALL')[] = ['ALL', 'CUSTOMER', 'PARTNER', 'ADMIN']
const STATUSES: (UserStatus | 'ALL')[] = ['ALL', 'ACTIVE', 'PENDING', 'SUSPENDED', 'DISABLED']

export function AdminUsersPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const compact = useIsCompact()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [role, setRole] = useState<UserRole | 'ALL'>('ALL')
  const [status, setStatus] = useState<UserStatus | 'ALL'>('ALL')

  const usersQuery = useQuery({
    queryKey: ['admin', 'users', role, status, page],
    queryFn: () =>
      adminApi.users({
        page,
        size: 10,
        role: role === 'ALL' ? undefined : role,
        status: status === 'ALL' ? undefined : status,
      }),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['admin', 'users'] })

  /* Held while the admin confirms; null means no dialog is open. */
  const [pendingSuspend, setPendingSuspend] = useState<AdminUserResponse | null>(null)

  const suspendMutation = useMutation({
    mutationFn: (userId: number) => adminApi.suspendUser(userId),
    onSuccess: invalidate,
  })

  const reactivateMutation = useMutation({
    mutationFn: (userId: number) => adminApi.reactivateUser(userId),
    onSuccess: invalidate,
  })

  const { rows, page: pageData, isEmpty } = readPage(usersQuery.data)

  return (
    <>
      <PageHeader title={t('admin.usersTitle')} description={t('admin.usersSubtitle')} />

      {/*
        Out of the page header and into a bar of their own. Two unlabelled
        dropdowns tucked beside a title are a guess about what they filter, and
        on a phone they were squeezed against the heading.
      */}
      <FilterBar className="mb-4">
        <Field label={t('admin.filterRole')}>
          <Select
            value={role}
            onChange={(event) => {
              setRole(event.target.value as UserRole | 'ALL')
              setPage(0)
            }}
            className="w-full sm:w-40"
          >
            {ROLES.map((value) => (
              <option key={value} value={value}>
                {value === 'ALL' ? t('admin.allRoles') : t('roles.' + value)}
              </option>
            ))}
          </Select>
        </Field>

        <Field label={t('admin.filterStatus')}>
          <Select
            value={status}
            onChange={(event) => {
              setStatus(event.target.value as UserStatus | 'ALL')
              setPage(0)
            }}
            className="w-full sm:w-40"
          >
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {value === 'ALL' ? t('partner.allStatuses') : label('userStatus', value)}
              </option>
            ))}
          </Select>
        </Field>
      </FilterBar>

      {(usersQuery.error || suspendMutation.error || reactivateMutation.error) && (
        <div className="mb-4">
          <ErrorMessage
            error={usersQuery.error ?? suspendMutation.error ?? reactivateMutation.error}
          />
        </div>
      )}

      <Card>
        {usersQuery.isLoading && (
          <div className="p-5">
            <Spinner />
          </div>
        )}

        {usersQuery.isError && !usersQuery.isLoading && (
          <EmptyState
            title={t('errors.loadFailed')}
            description={t('admin.loadFailedHint')}
          />
        )}

        {!usersQuery.isLoading && !usersQuery.isError && isEmpty && (
          <EmptyState title={t('admin.noUsers')} description={t('admin.noUsersHint')} />
        )}

        {rows.length > 0 && (
          <>
            {compact ? (
              <div className="space-y-2 p-4">
                {rows.map((user) => (
                  <div key={user.userId} className="rounded-xl border border-ink-200/70 p-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <p className="truncate font-semibold text-ink-900">
                          {user.firstName} {user.lastName}
                        </p>
                        <p className="tnum truncate text-xs text-ink-500">{user.phone}</p>
                        {user.email && (
                          <p className="truncate text-xs text-ink-500">{user.email}</p>
                        )}
                      </div>
                      <StatusBadge status={user.status} namespace="userStatus" />
                    </div>

                    <div className="mt-3 flex items-center justify-between gap-3">
                      <div className="flex items-center gap-2">
                        <Badge tone={user.role === 'ADMIN' ? 'info' : 'neutral'}>
                          {t('roles.' + user.role)}
                        </Badge>
                        <span className="tnum text-xs text-ink-400">
                          {formatDateTime(user.createdAt)}
                        </span>
                      </div>

                      {user.status === 'SUSPENDED' ? (
                        <Button
                          size="sm"
                          variant="secondary"
                          loading={
                            reactivateMutation.isPending &&
                            reactivateMutation.variables === user.userId
                          }
                          onClick={() => reactivateMutation.mutate(user.userId)}
                        >
                          {t('admin.reactivate')}
                        </Button>
                      ) : (
                        /* Spelled out on a phone: there is room, and no hover to reveal a title. */
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-bad-600 hover:bg-bad-50"
                          onClick={() => setPendingSuspend(user)}
                        >
                          <UserX className="size-4" aria-hidden />
                          {t('admin.suspend')}
                        </Button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
            <Table>
              <thead>
                <tr>
                  <Th>{t('nav.users')}</Th>
                  <Th>{t('profile.role')}</Th>
                  <Th>{t('ride.status')}</Th>
                  <Th>{t('admin.joined')}</Th>
                  <Th className="text-right" />
                </tr>
              </thead>
              <tbody>
                {rows.map((user) => (
                  <tr key={user.userId}>
                    <Td>
                      <span className="font-medium">
                        {user.firstName} {user.lastName}
                      </span>
                      <span className="block text-xs text-ink-500">
                        {user.phone}
                        {user.email ? ` · ${user.email}` : ''}
                      </span>
                    </Td>
                    <Td>
                      <Badge tone={user.role === 'ADMIN' ? 'info' : 'neutral'}>
                        {t('roles.' + user.role)}
                      </Badge>
                    </Td>
                    <Td>
                      <StatusBadge status={user.status} namespace="userStatus" />
                    </Td>
                    <Td className="tnum whitespace-nowrap text-xs text-ink-600">
                      {formatDateTime(user.createdAt)}
                    </Td>
                    <Td>
                      <RowActions>
                        {user.status === 'SUSPENDED' ? (
                          /*
                            Reactivate keeps its word: it is the rarer action and
                            the one an admin wants to be sure they are clicking.
                          */
                          <Button
                            size="sm"
                            variant="secondary"
                            loading={
                              reactivateMutation.isPending &&
                              reactivateMutation.variables === user.userId
                            }
                            onClick={() => reactivateMutation.mutate(user.userId)}
                          >
                            {t('admin.reactivate')}
                          </Button>
                        ) : (
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-ink-400 hover:bg-bad-50 hover:text-bad-600"
                            title={t('admin.suspend')}
                            aria-label={t('admin.suspend')}
                            onClick={() => setPendingSuspend(user)}
                          >
                            <UserX className="size-4" aria-hidden />
                          </Button>
                        )}
                      </RowActions>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            )}
            <Pagination page={pageData} onPageChange={setPage} />
          </>
        )}
      </Card>

      <ConfirmDialog
        open={pendingSuspend !== null}
        title={t('confirm.suspendUser.title', {
          name: pendingSuspend && `${pendingSuspend.firstName} ${pendingSuspend.lastName}`,
        })}
        description={t('confirm.suspendUser.body')}
        confirmLabel={t('confirm.suspendUser.action')}
        cancelLabel={t('confirm.keep')}
        loading={suspendMutation.isPending}
        onCancel={() => setPendingSuspend(null)}
        onConfirm={() =>
          pendingSuspend &&
          suspendMutation.mutate(pendingSuspend.userId, {
            onSuccess: () => setPendingSuspend(null),
          })
        }
      />
    </>
  )
}
