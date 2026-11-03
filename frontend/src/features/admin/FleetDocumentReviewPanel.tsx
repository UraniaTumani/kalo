import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { DocumentVerificationStatus } from '@/lib/api/types'
import { api } from '@/lib/api/client'
import { Button, Card, CardBody, Select, Textarea } from '@/components/ui'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'

type OwnerType = 'DRIVER' | 'VEHICLE'
type Owner = { id: number; label: string }
type Owners = { items: Owner[]; hasMore: boolean }
type Document = { id: number; documentType: string; documentNumber: string | null; issuedAt: string | null;
  expiresAt: string | null; verificationStatus: DocumentVerificationStatus; rejectionReason: string | null;
  reviewedByUserId?: number | null; reviewedAt?: string | null }
type Load<T> = { kind: 'loading' } | { kind: 'error' } | { kind: 'ready'; data: T }

export function FleetDocumentReviewPanel({ companyId, name, onClose, onBusyChange }: { companyId: number; name: string; onClose: () => void; onBusyChange: (busy: boolean) => void }) {
  const { t } = useTranslation()
  const [type, setType] = useState<OwnerType>('DRIVER')
  const [page, setPage] = useState(0)
  const [owner, setOwner] = useState<Owner | null>(null)
  const [refresh, setRefresh] = useState(0)
  const [owners, setOwners] = useState<Load<Owners>>({ kind: 'loading' })
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let alive = true
    setOwners({ kind: 'loading' })
    void api.get<Owners>(`/api/v1/admin/partners/${companyId}/fleet/owners`, { ownerType: type, page })
      .then(data => {
        if (!data || !Array.isArray(data.items) || typeof data.hasMore !== 'boolean'
          || !data.items.every(o => Number.isSafeInteger(o.id) && typeof o.label === 'string')) throw new Error('Invalid owners')
        if (alive) setOwners({ kind: 'ready', data })
      })
      .catch(() => { if (alive) setOwners({ kind: 'error' }) })
    return () => { alive = false }
  }, [companyId, type, page, refresh])
  return <Card className="mb-4"><CardBody className="space-y-4">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h2 className="font-semibold">{t('fleetReview.title')} · {name}</h2>
      <Button disabled={busy} onClick={onClose}>{t('common.close')}</Button>
    </div>
    <p className="text-sm text-ink-600">{t('fleetReview.hint')}</p>
    <label className="block text-sm">{t('fleetReview.ownerType')}
      <Select aria-label={t('fleetReview.ownerType')} value={type} disabled={busy} onChange={e => { setType(e.target.value as OwnerType); setPage(0); setOwner(null) }}>
        <option value="DRIVER">{t('fleetReview.drivers')}</option><option value="VEHICLE">{t('fleetReview.vehicles')}</option>
      </Select>
    </label>
    {owners.kind === 'loading' && <p>{t('fleetReview.loading')}</p>}
    {owners.kind === 'error' && <div><p role="alert">{t('fleetReview.loadError')}</p><Button onClick={() => setRefresh(v => v + 1)}>{t('fleetReview.refresh')}</Button></div>}
    {owners.kind === 'ready' && <>
      {owners.data.items.length === 0 ? <p>{t('fleetReview.noOwners')}</p> : <label className="block text-sm">{t('fleetReview.owner')}
        <Select aria-label={t('fleetReview.owner')} disabled={busy} value={owner?.id ?? ''} onChange={e => setOwner(owners.data.items.find(o => o.id === Number(e.target.value)) ?? null)}>
          <option value="">{t('fleetReview.choose')}</option>
          {owners.data.items.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}
        </Select>
      </label>}
      <div className="flex gap-2">
        <Button disabled={busy || page === 0} onClick={() => { setOwner(null); setPage(p => p - 1) }}>{t('fleetReview.previous')}</Button>
        <Button disabled={busy || !owners.data.hasMore} onClick={() => { setOwner(null); setPage(p => p + 1) }}>{t('fleetReview.next')}</Button>
      </div>
    </>}
    {owner && <Documents key={`${companyId}-${type}-${owner.id}`} companyId={companyId} type={type} owner={owner} onBusy={value => { setBusy(value); onBusyChange(value) }} />}
  </CardBody></Card>
}

function Documents({ companyId, type, owner, onBusy }: { companyId: number; type: OwnerType; owner: Owner; onBusy: (busy: boolean) => void }) {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const base = `/api/v1/admin/partners/${companyId}/fleet/${type}/${owner.id}/documents`
  const [result, setResult] = useState<Load<Document[]>>({ kind: 'loading' })
  const [refresh, setRefresh] = useState(0)
  const [history, setHistory] = useState(false)
  const [reason, setReason] = useState<Record<number, string>>({})
  const [confirmation, setConfirmation] = useState<{ document: Document; action: 'approve' | 'reject'; reason: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(false)
  const busyRef = useRef(false)
  const alive = useRef(true)
  const epoch = useRef(0)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    let mounted = true
    const version = epoch.current
    void api.get<Document[]>(`${base}${history ? "/history" : ""}`).then(data => {
      if (!Array.isArray(data) || !data.every(d => Number.isSafeInteger(d.id) && typeof d.documentType === 'string'
        && ['DRAFT', 'PENDING', 'APPROVED', 'REJECTED'].includes(d.verificationStatus))) throw new Error('Invalid documents')
      if (mounted && version === epoch.current) setResult({ kind: 'ready', data })
    }).catch(() => { if (mounted && version === epoch.current) setResult({ kind: 'error' }) })
    return () => { mounted = false }
  }, [base, refresh, history])
  function pending(value: boolean) { busyRef.current = value; setBusy(value); onBusy(value) }
  function reload() { setConfirmation(null); setResult({ kind: 'loading' }); setRefresh(v => v + 1) }
  async function decide() {
    if (history || busyRef.current || !confirmation || result.kind !== 'ready') return
    const current = result.data.find(d => d.id === confirmation.document.id)
    if (!current || !['DRAFT', 'PENDING'].includes(current.verificationStatus)) { reload(); return }
    pending(true); epoch.current++; setError(false)
    try {
      await api.post(`${base}/${current.id}/${confirmation.action}`,
        confirmation.action === 'reject' ? { reason: confirmation.reason } : undefined, { skipRefresh: true })
    } catch { if (alive.current) setError(true) }
    finally { if (alive.current) { pending(false); reload() } }
  }
  async function download(id: number) {
    if (busyRef.current) return
    pending(true); setError(false)
    try {
      const blob = await api.download(`${base}/${id}/file`)
      if (!alive.current) return
      const url = URL.createObjectURL(blob)
      const a = window.document.createElement('a')
      a.href = url; a.download = `document-${id}.${blob.type === 'application/pdf' ? 'pdf' : blob.type === 'image/png' ? 'png' : 'jpg'}`
      window.document.body.appendChild(a); a.click(); a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch { if (alive.current) setError(true) }
    finally { if (alive.current) pending(false) }
  }
  return <section aria-label={t('fleetReview.documents')} className="space-y-3">
    <h3 className="font-semibold">{owner.label}</h3>
    <Button disabled={busy || result.kind === 'loading'} onClick={() => {
      if (busyRef.current) return
      epoch.current++; setConfirmation(null); setReason({}); setError(false)
      setResult({ kind: 'loading' }); setHistory(value => !value)
    }}>{t(history ? 'fleetAudit.showCurrent' : 'fleetAudit.showHistory')}</Button>
    {history && <p className="text-sm text-ink-600">{t('fleetAudit.historyHint')}</p>}
    {error && <p role="alert">{t('fleetReview.actionError')}</p>}
    {result.kind === 'loading' && <p>{t('fleetReview.loading')}</p>}
    {result.kind === 'error' && <p role="alert">{t('fleetReview.loadError')}</p>}
    {result.kind === 'ready' && (result.data.length === 0 ? <p>{t('fleetReview.noDocuments')}</p> : result.data.map(d => {
      const reviewable = !history && ['DRAFT', 'PENDING'].includes(d.verificationStatus)
      const reviewedAt = typeof d.reviewedAt === 'string' && Number.isFinite(Date.parse(d.reviewedAt)) ? d.reviewedAt : null
      const recorded = Number.isSafeInteger(d.reviewedByUserId) && (d.reviewedByUserId ?? 0) > 0 && reviewedAt
      return <article key={d.id} className="space-y-2 break-words rounded-lg border border-ink-200 p-3">
        <h4 className="font-medium">{label('documentType', d.documentType)}</h4>
        <StatusBadge status={d.verificationStatus} namespace="documentStatus" />
        <p className="text-sm">{t('fleetAudit.version', { id: d.id })}</p>
        {recorded && reviewedAt ? <p className="text-sm">{t('fleetAudit.reviewer', { id: d.reviewedByUserId })} · <time dateTime={reviewedAt}>{new Date(reviewedAt).toLocaleString()}</time></p>
          : ['APPROVED', 'REJECTED'].includes(d.verificationStatus) && <p className="text-sm text-ink-600">{t('fleetAudit.unknown')}</p>}
        <p className="text-sm">{t('fleetReview.number')}: {d.documentNumber || '—'}</p>
        <p className="text-sm">{t('fleetReview.issued')}: {d.issuedAt || '—'} · {t('fleetReview.expires')}: {d.expiresAt || '—'}</p>
        {d.rejectionReason && <p className="text-sm">{t('fleetReview.reason')}: {d.rejectionReason}</p>}
        <Button disabled={busy} onClick={() => { void download(d.id) }}>{t('fleetReview.download')}</Button>
        {reviewable && <div className="space-y-2">
          <Button disabled={busy} onClick={() => setConfirmation({ document: d, action: 'approve', reason: '' })}>{t('fleetReview.approve')}</Button>
          <label className="block text-sm">{t('fleetReview.reason')}
            <Textarea aria-label={t('fleetReview.reason')} disabled={busy} maxLength={1000} value={reason[d.id] ?? ''} onChange={e => setReason(r => ({ ...r, [d.id]: e.target.value }))} />
          </label>
          <Button disabled={busy || !(reason[d.id] ?? '').trim()} onClick={() => setConfirmation({ document: d, action: 'reject', reason: reason[d.id].trim() })}>{t('fleetReview.reject')}</Button>
        </div>}
      </article>
    }))}
    <Button disabled={busy || result.kind === 'loading'} onClick={reload}>{t('fleetReview.refresh')}</Button>
    <ConfirmDialog open={confirmation !== null} title={t('fleetReview.confirmTitle')}
      description={<>{confirmation && <><p>{owner.label} · {label('documentType', confirmation.document.documentType)}</p><p>{t('fleetReview.confirmHint')}</p>{confirmation.reason && <p className="break-words">{confirmation.reason}</p>}</>}</>}
      confirmLabel={t(confirmation?.action === 'reject' ? 'fleetReview.reject' : 'fleetReview.approve')}
      loading={busy} onCancel={() => { if (!busyRef.current) setConfirmation(null) }} onConfirm={() => { void decide() }} />
  </section>
}
