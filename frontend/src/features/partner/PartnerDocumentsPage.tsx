import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import { useState } from 'react'
import type { DocumentResponse, DocumentType } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Spinner } from '@/components/ui/Spinner'
import {
  Alert,
  Button,
  Card,
  CardBody,
  CardHeader,
  EmptyState,
  Field,
  Input,
  Select,
  Table,
  Td,
  Th,
} from '@/components/ui'

const COMPANY_DOCUMENT_TYPES: DocumentType[] = ['BUSINESS_REGISTRATION', 'TAXI_LICENSE']

const documentSchema = z.object({
  documentType: z.enum(['BUSINESS_REGISTRATION', 'TAXI_LICENSE']),
  documentNumber: z.string().trim().max(100).optional(),
  fileUrl: z.url('validation.urlInvalid').max(1000),
  issuedAt: z.string().optional(),
  expiresAt: z.string().optional(),
})

type DocumentValues = z.infer<typeof documentSchema>

export function PartnerDocumentsPage() {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const queryClient = useQueryClient()

  const profileQuery = useQuery({
    queryKey: ['partner', 'profile'],
    queryFn: () => partnerApi.profile(),
  })

  const documentsQuery = useQuery({
    queryKey: ['partner', 'documents'],
    queryFn: () => partnerApi.documents(),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['partner'] })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<DocumentValues>({
    resolver: zodResolver(documentSchema),
    defaultValues: { documentType: 'BUSINESS_REGISTRATION' },
  })

  const createMutation = useMutation({
    mutationFn: (values: DocumentValues) =>
      partnerApi.addDocument({
        ...values,
        documentNumber: values.documentNumber || null,
        issuedAt: values.issuedAt || null,
        expiresAt: values.expiresAt || null,
      }),
    onSuccess: () => {
      invalidate()
      reset()
    },
  })

  /* Held while the partner confirms; null means no dialog is open. */
  const [pendingDelete, setPendingDelete] = useState<DocumentResponse | null>(null)

  const deleteMutation = useMutation({
    mutationFn: (documentId: number) => partnerApi.deleteDocument(documentId),
    onSuccess: invalidate,
  })

  const submitMutation = useMutation({
    mutationFn: () => partnerApi.submitVerification(),
    onSuccess: invalidate,
  })

  const verificationStatus = profileQuery.data?.verificationStatus
  const canSubmit = verificationStatus === 'DRAFT' || verificationStatus === 'REJECTED'
  const documents = documentsQuery.data ?? []

  /*
   * The company fields the backend checks in validateCompanyProfile live on
   * the Settings page, so without this a partner only discovers a missing one
   * as a rejection from Submit, with no hint of where to fix it.
   */
  const profile = profileQuery.data
  const missingProfileFields = profile
    ? [
        !profile.legalName?.trim() && 'Legal name',
        !profile.displayName?.trim() && 'Display name',
        !profile.nipt?.trim() && 'NIPT',
        !profile.phone?.trim() && 'Company phone',
        !profile.address?.trim() && 'Company address',
        !profile.licenseNumber?.trim() && 'Taxi licence number',
        !profile.licenseExpiryDate && 'Taxi licence expiry date',
      ].filter((field): field is string => Boolean(field))
    : []

  const requiredDocumentTypes: DocumentType[] = ['BUSINESS_REGISTRATION', 'TAXI_LICENSE']
  const missingDocuments = requiredDocumentTypes.filter(
    (type) => !documents.some((document) => document.documentType === type),
  )

  const readyToSubmit = missingProfileFields.length === 0 && missingDocuments.length === 0

  return (
    <>
      <PageHeader
        title={t('partner.documentsTitle')}
        description={t('partner.documentsSubtitle')}
        action={
          canSubmit ? (
            <Button
              disabled={!readyToSubmit}
              loading={submitMutation.isPending}
              onClick={() => submitMutation.mutate()}
            >
              {t('partner.submitVerification')}
            </Button>
          ) : undefined
        }
      />

      {submitMutation.error && (
        <div className="mb-4">
          <ErrorMessage error={submitMutation.error} />
        </div>
      )}

      {submitMutation.isSuccess && (
        <div className="mb-4">
          <Alert tone="success" title={t('common.saved')}>
            {submitMutation.data.message}
          </Alert>
        </div>
      )}

      {canSubmit && !readyToSubmit && !profileQuery.isLoading && (
        <div className="mb-4">
          <Alert tone="warning" title={t('partner.notReady')}>
            {missingProfileFields.length > 0 && (
              <p>
                {t('partner.missingProfile')}:{' '}
                <Link to="/partner/settings" className="font-medium underline">
                  {t('partner.companyProfile')}
                </Link>
                {missingProfileFields.join(', ')}.
              </p>
            )}
            {missingDocuments.length > 0 && (
              <p className={missingProfileFields.length > 0 ? 'mt-1' : undefined}>
                {t('partner.missingDocuments')}:{' '}
                {missingDocuments.map((type) => label('documentType', type)).join(', ')}.
              </p>
            )}
          </Alert>
        </div>
      )}

      {verificationStatus === 'PENDING' && (
        <div className="mb-4">
          <Alert tone="info" title={t('partner.underReview')}>
            {t('partner.underReviewHint')}
          </Alert>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <Card>
          {documentsQuery.isLoading && (
            <div className="p-5">
              <Spinner />
            </div>
          )}

          {documents.length === 0 && !documentsQuery.isLoading && (
            <EmptyState
              title={t('partner.noDocumentsYet')}
              description={t('partner.noDocumentsHint')}
            />
          )}

          {documents.length > 0 && (
            <Table>
              <thead>
                <tr>
                  <Th>{t('partner.type')}</Th>
                  <Th>{t('partner.documentNumber')}</Th>
                  <Th>{t('ride.status')}</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {documents.map((document) => (
                  <tr key={document.id}>
                    <Td>
                      <span className="font-medium">{label('documentType', document.documentType)}</span>
                      <a
                        href={document.fileUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="block truncate text-xs text-brand-700 hover:underline"
                      >
                        {document.fileUrl}
                      </a>
                    </Td>
                    <Td className="text-xs">{document.documentNumber ?? '—'}</Td>
                    <Td>
                      <StatusBadge status={document.verificationStatus} namespace="documentStatus" />
                      {document.rejectionReason && (
                        <span className="mt-1 block text-xs text-red-600">
                          {document.rejectionReason}
                        </span>
                      )}
                    </Td>
                    <Td>
                      {document.verificationStatus !== 'APPROVED' && (
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-red-600 hover:bg-red-50"
                          onClick={() => setPendingDelete(document)}
                        >
                          {t('common.remove')}
                        </Button>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <Card>
          <CardHeader title={t('partner.addDocument')} description={t('partner.documentUrlHint')} />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => createMutation.mutate(values))}
              noValidate
            >
              {createMutation.error && <ErrorMessage error={createMutation.error} />}
              {deleteMutation.error && <ErrorMessage error={deleteMutation.error} />}

              <Field label={t('partner.type')} error={errors.documentType ? t(errors.documentType.message!) : undefined} required>
                <Select {...register('documentType')}>
                  {COMPANY_DOCUMENT_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {label('documentType', type)}
                    </option>
                  ))}
                </Select>
              </Field>

              <Field label={t('partner.fileUrl')} error={errors.fileUrl ? t(errors.fileUrl.message!) : undefined} required>
                <Input {...register('fileUrl')} placeholder="https://example.com/licence.pdf" />
              </Field>

              <Field label={t('partner.documentNumber')} error={errors.documentNumber ? t(errors.documentNumber.message!) : undefined}>
                <Input {...register('documentNumber')} />
              </Field>

              <Field label={t('partner.issuedAt')} error={errors.issuedAt ? t(errors.issuedAt.message!) : undefined}>
                <Input {...register('issuedAt')} type="date" />
              </Field>

              <Field label={t('partner.expiresAt')} error={errors.expiresAt ? t(errors.expiresAt.message!) : undefined}>
                <Input {...register('expiresAt')} type="date" />
              </Field>

              <Button type="submit" className="w-full" loading={createMutation.isPending}>
                {t('partner.addDocument')}
              </Button>
            </form>
          </CardBody>
        </Card>
      </div>

      <ConfirmDialog
        open={pendingDelete !== null}
        title={t('confirm.deleteDocument.title', {
          name: label('documentType', pendingDelete?.documentType),
        })}
        description={t('confirm.deleteDocument.body')}
        confirmLabel={t('confirm.deleteDocument.action')}
        cancelLabel={t('confirm.keep')}
        loading={deleteMutation.isPending}
        onCancel={() => setPendingDelete(null)}
        onConfirm={() =>
          pendingDelete &&
          deleteMutation.mutate(pendingDelete.id, {
            onSuccess: () => setPendingDelete(null),
          })
        }
      />
    </>
  )
}
