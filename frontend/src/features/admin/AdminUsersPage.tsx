import { useTranslation } from 'react-i18next'
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import { readPage } from '@/lib/api/page'
import type { AdminUserResponse, UserRole, UserStatus } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Spinner } from '@/components/ui/Spinner'
import { Badge, Button, Card, EmptyState, Select, Table, Td, Th } from '@/components/ui'
import { formatDateTime } from '@/lib/utils'

const ROLES: (UserRole | 'ALL')[] = ['ALL', 'CUSTOMER', 'PARTNER', 'ADMIN']
const STATUSES: (UserStatus | 'ALL')[] = ['ALL', 'ACTIVE', 'PENDING', 'SUSPENDED', 'DISABLED']

export function AdminUsersPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
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
      <PageHeader
        title={t('admin.usersTitle')}
        description={t('admin.usersSubtitle')}
        action={
          <div className="flex gap-2">
            <Select
              value={role}
              onChange={(event) => {
                setRole(event.target.value as UserRole | 'ALL')
                setPage(0)
              }}
              className="w-36"
            >
              {ROLES.map((value) => (
                <option key={value} value={value}>
                  {value === 'ALL' ? t('admin.allRoles') : t('roles.' + value)}
                </option>
              ))}
            </Select>
            <Select
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as UserStatus | 'ALL')
                setPage(0)
              }}
              className="w-36"
            >
              {STATUSES.map((value) => (
                <option key={value} value={value}>
                  {value === 'ALL' ? t('partner.allStatuses') : label('userStatus', value)}
                </option>
              ))}
            </Select>
          </div>
        }
      />

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

          />
        )}

        {!usersQuery.isLoading && !usersQuery.isError && isEmpty && (
          <EmptyState title={t('admin.noUsers')} />
        )}

        {rows.length > 0 && (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>{t('nav.users')}</Th>
                  <Th>{t('profile.role')}</Th>
                  <Th>{t('ride.status')}</Th>
                  <Th>{t('admin.joined')}</Th>
                  <Th />
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
                    <Td className="text-xs">{formatDateTime(user.createdAt)}</Td>
                    <Td>
                      {user.status === 'SUSPENDED' ? (
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => reactivateMutation.mutate(user.userId)}
                        >
                          {t('admin.reactivate')}
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-red-600 hover:bg-red-50"
                          onClick={() => setPendingSuspend(user)}
                        >
                          {t('admin.suspend')}
                        </Button>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
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
