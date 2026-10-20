import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { partnerApi } from '@/lib/api/endpoints'
import type { DocumentType } from '@/lib/api/types'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { ErrorMessage } from '@/components/ErrorMessage'
import { Spinner } from '@/components/ui/Spinner'
import { Alert, Button, Card, CardBody, CardHeader, Field, Input, Select } from '@/components/ui'

const TYPES: Record<'driver' | 'vehicle', DocumentType[]> = {
  driver: ['DRIVING_LICENSE', 'PROFESSIONAL_CERTIFICATE'],
  vehicle: ['VEHICLE_REGISTRATION', 'INSURANCE', 'PASSENGER_INSURANCE', 'TECHNICAL_INSPECTION', 'TAXIMETER_VERIFICATION'],
}

/** Mounted only for the selected owner; the parent keys it by owner id. */
export function FleetDocumentsCard({ owner, ownerId, name, onClose }: {
  owner: 'driver' | 'vehicle'
  ownerId: number
  name: string
  onClose: () => void
}) {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const queryClient = useQueryClient()
  const queryKey = ['partner', owner, ownerId, 'documents']
  const [documentType, setDocumentType] = useState<DocumentType>(TYPES[owner][0])
  const [file, setFile] = useState<File | null>(null)
  const [fileKey, setFileKey] = useState(0)
  const [fileError, setFileError] = useState<string | null>(null)
  const documentsQuery = useQuery({
    queryKey,
    queryFn: () => owner === 'driver' ? partnerApi.driverDocuments(ownerId) : partnerApi.vehicleDocuments(ownerId),
  })
  const documents = documentsQuery.data ?? []
  const duplicate = documents.some((document) => document.documentType === documentType)

  const upload = useMutation({
    mutationFn: (body: FormData) => owner === 'driver'
      ? partnerApi.uploadDriverDocument(ownerId, body)
      : partnerApi.uploadVehicleDocument(ownerId, body),
    onSuccess: async () => {
      setFile(null)
      setFileError(null)
      setFileKey((key) => key + 1)
      await queryClient.invalidateQueries({ queryKey })
    },
  })
  const download = useMutation({
    mutationFn: (id: number) => owner === 'driver'
      ? partnerApi.downloadDriverDocument(ownerId, id)
      : partnerApi.downloadVehicleDocument(ownerId, id),
    onSuccess: (blob, id) => {
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      const extension = blob.type === 'application/pdf' ? 'pdf' : blob.type === 'image/png' ? 'png' : 'jpg'
      anchor.href = url
      anchor.download = `document-${id}.${extension}`
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    },
  })

  return (
    <Card>
      <CardHeader title={`${t('partner.documentsTitle')} · ${name}`} />
      <CardBody>
        <div className="space-y-4">
          <Button variant="secondary" size="sm" onClick={onClose} disabled={upload.isPending}>
            {t('common.close')}
          </Button>
          {documentsQuery.isPending && <Spinner />}
          {documentsQuery.error && <ErrorMessage error={documentsQuery.error} />}
          {documentsQuery.isError && <Button variant="secondary" onClick={() => documentsQuery.refetch()}>{t('fleetDocuments.retry')}</Button>}
          {download.error && <ErrorMessage error={download.error} />}
          {documentsQuery.isSuccess && documents.length === 0 && <p className="text-sm text-ink-500">{t('partner.noDocumentsYet')}</p>}
          <ul className="space-y-3">
            {documents.map((item) => (
              <li key={item.id} className="min-w-0 rounded-lg border border-ink-200 p-3">
                <p className="break-words font-medium">{label('documentType', item.documentType)}</p>
                <p className="break-words text-sm text-ink-500">{item.documentNumber ?? '—'}</p>
                <p className="text-xs text-ink-500">{t('partner.issuedAt')}: {item.issuedAt ?? '—'}</p>
                <p className="text-xs text-ink-500">{t('partner.expiresAt')}: {item.expiresAt ?? '—'}</p>
                <StatusBadge status={item.verificationStatus} namespace="documentStatus" />
                {item.rejectionReason && <p className="break-words text-sm text-red-600">{item.rejectionReason}</p>}
                <div className="mt-2">
                  <Button size="sm" variant="secondary" disabled={download.isPending} onClick={() => download.mutate(item.id)}>
                    {t('partner.downloadDocument')}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
          <form className="space-y-3" onSubmit={(event) => {
            event.preventDefault()
            if (upload.isPending || duplicate || !documentsQuery.isSuccess) return
            if (!file) {
              setFileError(t('partner.uploadFileRequired'))
              return
            }
            const body = new FormData(event.currentTarget)
            body.set('file', file)
            for (const key of ['documentNumber', 'issuedAt', 'expiresAt']) {
              const value = String(body.get(key) ?? '').trim()
              if (value) body.set(key, value)
              else body.delete(key)
            }
            upload.mutate(body)
          }}>
            {upload.error && <ErrorMessage error={upload.error} />}
            {upload.isSuccess && <div role="status"><Alert tone="success" title={t('common.saved')} /></div>}
            <Field label={t('partner.type')} required>
              <Select name="documentType" value={documentType} disabled={upload.isPending} onChange={(event) => {
                setDocumentType(event.target.value as DocumentType)
                upload.reset()
              }}>
                {TYPES[owner].map((type) => <option key={type} value={type}>{label('documentType', type)}</option>)}
              </Select>
            </Field>
            {duplicate && <p role="status" className="text-sm text-ink-500">{t('fleetDocuments.duplicate')}</p>}
            <Field label={t('partner.uploadFile')} error={fileError ?? undefined} hint={t('partner.documentUploadHint')} required>
              <input key={fileKey} type="file" name="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
                disabled={upload.isPending} className="block w-full min-w-0 rounded-lg border border-ink-200 p-2 text-sm"
                onChange={(event) => {
                  const selected = event.target.files?.[0] ?? null
                  let error: string | null = null
                  if (selected && selected.size > 5 * 1024 * 1024) error = t('partner.uploadFileTooLarge')
                  else if (selected && (selected.size === 0 || !/\.(pdf|jpe?g|png)$/i.test(selected.name)
                    || (selected.type && !['application/pdf', 'image/jpeg', 'image/png'].includes(selected.type)))) error = t('fleetDocuments.invalidFile')
                  setFile(error ? null : selected)
                  setFileError(error)
                  upload.reset()
                }} />
            </Field>
            <Field label={t('partner.documentNumber')}>
              <Input name="documentNumber" maxLength={100} disabled={upload.isPending} />
            </Field>
            <Field label={t('partner.issuedAt')}>
              <Input name="issuedAt" type="date" disabled={upload.isPending} onChange={(event) => {
                const expiry = event.currentTarget.form?.elements.namedItem('expiresAt') as HTMLInputElement | null
                if (expiry) expiry.min = event.currentTarget.value
              }} />
            </Field>
            <Field label={t('partner.expiresAt')}>
              <Input name="expiresAt" type="date" disabled={upload.isPending} />
            </Field>
            <Button type="submit" className="w-full" loading={upload.isPending} disabled={!documentsQuery.isSuccess || duplicate || !file}>
              {t('partner.addDocument')}
            </Button>
          </form>
        </div>
      </CardBody>
    </Card>
  )
}
