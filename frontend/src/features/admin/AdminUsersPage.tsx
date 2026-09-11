import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { adminApi } from '@/lib/api/endpoints'
import type { UserRole, UserStatus } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { Pagination } from '@/components/Pagination'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Badge, Button, Card, EmptyState, Select, Table, Td, Th } from '@/components/ui'
import { formatDateTime, humanise } from '@/lib/utils'

const ROLES: (UserRole | 'ALL')[] = ['ALL', 'CUSTOMER', 'PARTNER', 'ADMIN']
const STATUSES: (UserStatus | 'ALL')[] = ['ALL', 'ACTIVE', 'PENDING', 'SUSPENDED', 'DISABLED']

export function AdminUsersPage() {
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

  const suspendMutation = useMutation({
    mutationFn: (userId: number) => adminApi.suspendUser(userId),
    onSuccess: invalidate,
  })

  const reactivateMutation = useMutation({
    mutationFn: (userId: number) => adminApi.reactivateUser(userId),
    onSuccess: invalidate,
  })

  return (
    <>
      <PageHeader
        title="Users"
        description="Suspended users cannot sign in, and existing tokens stop working."
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
                  {value === 'ALL' ? 'All roles' : humanise(value)}
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
                  {value === 'ALL' ? 'All statuses' : humanise(value)}
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

        {usersQuery.data?.empty && <EmptyState title="No users match this filter" />}

        {usersQuery.data && !usersQuery.data.empty && (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>User</Th>
                  <Th>Role</Th>
                  <Th>Status</Th>
                  <Th>Joined</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {usersQuery.data.content.map((user) => (
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
                        {humanise(user.role)}
                      </Badge>
                    </Td>
                    <Td>
                      <StatusBadge status={user.status} />
                    </Td>
                    <Td className="text-xs">{formatDateTime(user.createdAt)}</Td>
                    <Td>
                      {user.status === 'SUSPENDED' ? (
                        <Button
                          size="sm"
                          variant="secondary"
                          onClick={() => reactivateMutation.mutate(user.userId)}
                        >
                          Reactivate
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-red-600 hover:bg-red-50"
                          onClick={() => suspendMutation.mutate(user.userId)}
                        >
                          Suspend
                        </Button>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={usersQuery.data} onPageChange={setPage} />
          </>
        )}
      </Card>
    </>
  )
}
