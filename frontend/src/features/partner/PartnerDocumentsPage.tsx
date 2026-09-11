import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { partnerApi } from '@/lib/api/endpoints'
import type { DocumentType } from '@/lib/api/types'
import { PageHeader } from '@/components/AppLayout'
import { StatusBadge } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
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
import { humanise } from '@/lib/utils'

const COMPANY_DOCUMENT_TYPES: DocumentType[] = ['BUSINESS_REGISTRATION', 'TAXI_LICENSE']

const documentSchema = z.object({
  documentType: z.enum(['BUSINESS_REGISTRATION', 'TAXI_LICENSE']),
  documentNumber: z.string().trim().max(100).optional(),
  fileUrl: z.url('Enter a valid URL').max(1000),
  issuedAt: z.string().optional(),
  expiresAt: z.string().optional(),
})

type DocumentValues = z.infer<typeof documentSchema>

export function PartnerDocumentsPage() {
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

  return (
    <>
      <PageHeader
        title="Documents"
        description="Upload your company paperwork, then submit for verification."
        action={
          canSubmit ? (
            <Button
              disabled={documents.length === 0}
              loading={submitMutation.isPending}
              onClick={() => submitMutation.mutate()}
            >
              Submit for verification
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
          <Alert tone="success" title="Submitted">
            {submitMutation.data.message}
          </Alert>
        </div>
      )}

      {verificationStatus === 'PENDING' && (
        <div className="mb-4">
          <Alert tone="info" title="Under review">
            An administrator is reviewing your documents. You cannot change them right now.
          </Alert>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[1fr_20rem]">
        <Card>
          {documentsQuery.isLoading && (
            <div className="p-5">
              <Spinner />
            </div>
          )}

          {documents.length === 0 && !documentsQuery.isLoading && (
            <EmptyState
              title="No documents"
              description="Add your business registration and taxi licence."
            />
          )}

          {documents.length > 0 && (
            <Table>
              <thead>
                <tr>
                  <Th>Type</Th>
                  <Th>Number</Th>
                  <Th>Status</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {documents.map((document) => (
                  <tr key={document.id}>
                    <Td>
                      <span className="font-medium">{humanise(document.documentType)}</span>
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
                      <StatusBadge status={document.verificationStatus} />
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
                          onClick={() => deleteMutation.mutate(document.id)}
                        >
                          Remove
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
          <CardHeader title="Add a document" description="Hosted file URL — upload is not part of the MVP." />
          <CardBody>
            <form
              className="space-y-3"
              onSubmit={handleSubmit((values) => createMutation.mutate(values))}
              noValidate
            >
              {createMutation.error && <ErrorMessage error={createMutation.error} />}
              {deleteMutation.error && <ErrorMessage error={deleteMutation.error} />}

              <Field label="Type" error={errors.documentType?.message} required>
                <Select {...register('documentType')}>
                  {COMPANY_DOCUMENT_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {humanise(type)}
                    </option>
                  ))}
                </Select>
              </Field>

              <Field label="File URL" error={errors.fileUrl?.message} required>
                <Input {...register('fileUrl')} placeholder="https://example.com/licence.pdf" />
              </Field>

              <Field label="Document number" error={errors.documentNumber?.message}>
                <Input {...register('documentNumber')} />
              </Field>

              <Field label="Issued at" error={errors.issuedAt?.message}>
                <Input {...register('issuedAt')} type="date" />
              </Field>

              <Field label="Expires at" error={errors.expiresAt?.message}>
                <Input {...register('expiresAt')} type="date" />
              </Field>

              <Button type="submit" className="w-full" loading={createMutation.isPending}>
                Add document
              </Button>
            </form>
          </CardBody>
        </Card>
      </div>
    </>
  )
}
