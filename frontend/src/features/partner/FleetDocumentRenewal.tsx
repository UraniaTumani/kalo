import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { DocumentResponse } from '@/lib/api/types'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { StatusBadge, useStatusLabel } from '@/components/StatusBadge'
import { Button, Input } from '@/components/ui'

function todayInTirana() {
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Europe/Tirane', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const get = (part: string) => parts.find(p => p.type === part)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}`
}
function renewable(d: DocumentResponse) {
  return d.verificationStatus === 'REJECTED' || (Boolean(d.expiresAt) && d.expiresAt! < todayInTirana())
}

export function FleetDocumentRenewal({ owner, ownerId, documents, loading, onBusy }: {
  owner: 'driver' | 'vehicle'; ownerId: number; documents: DocumentResponse[]; loading: boolean; onBusy: (value: boolean) => void
}) {
  const { t } = useTranslation()
  const label = useStatusLabel()
  const client = useQueryClient()
  const key = ['partner', owner, ownerId, 'documents']
  const base = `/api/v1/partner/${owner === 'driver' ? 'drivers' : 'vehicles'}/${ownerId}/documents`
  const [selected, setSelected] = useState<number | null>(null)
  const [file, setFile] = useState<File | null>(null)
  const [fileKey, setFileKey] = useState(0)
  const [number, setNumber] = useState('')
  const [issued, setIssued] = useState('')
  const [expires, setExpires] = useState('')
  const [error, setError] = useState(false)
  const [validation, setValidation] = useState(false)
  const [saved, setSaved] = useState(false)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [confirmation, setConfirmation] = useState<{ id: number; body: FormData; filename: string; expires: string } | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const history = useQuery({ queryKey: [...key, 'history'], enabled: showHistory, retry: false,
    queryFn: async () => {
      const data = await api.get<DocumentResponse[]>(`${base}/history`)
      if (!Array.isArray(data)) throw new Error('Invalid document history')
      return data
    } })
  const current = documents.find(d => d.id === selected)
  function reset() { setSelected(null); setFile(null); setFileKey(k => k + 1); setNumber(''); setIssued(''); setExpires(''); setConfirmation(null) }
  function pending(value: boolean) { busyRef.current = value; setBusy(value); onBusy(value) }
  function choose(d: DocumentResponse) { reset(); setSelected(d.id); setError(false); setSaved(false); setValidation(false) }
  async function submit() {
    if (busyRef.current || !confirmation || loading) return
    if (!documents.some(d => d.id === confirmation.id && renewable(d))) { reset(); setError(true); return }
    const submission = confirmation
    pending(true); setError(false); setSaved(false)
    try {
      await client.cancelQueries({ queryKey: key })
      await api.post(`${base}/${submission.id}/renew`, submission.body, { skipRefresh: true })
      setSaved(true)
    } catch { setError(true) }
    finally {
      reset()
      // The upload may have committed even if its response was lost.
      await client.invalidateQueries({ queryKey: key })
      pending(false)
    }
  }
  async function download(id: number) {
    if (busyRef.current) return
    pending(true); setError(false)
    try {
      const blob = await api.download(`${base}/${id}/file`)
      const url = URL.createObjectURL(blob), a = document.createElement('a')
      a.href = url; a.download = `document-${id}.${blob.type === 'application/pdf' ? 'pdf' : blob.type === 'image/png' ? 'png' : 'jpg'}`
      document.body.appendChild(a); a.click(); a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch { setError(true) }
    finally { pending(false) }
  }
  return <section aria-label={t('fleetRenewal.title')} className="space-y-3 border-t border-ink-200 pt-4">
    <h3 className="font-semibold">{t('fleetRenewal.title')}</h3>
    <p className="text-sm text-ink-600">{t('fleetRenewal.hint')}</p>
    {error && <p role="alert">{t('fleetRenewal.error')}</p>}
    {saved && <p role="status">{t('fleetRenewal.saved')}</p>}
    {!loading && documents.filter(renewable).map(d => <div key={d.id} className="flex flex-wrap items-center gap-2">
      <span>{label('documentType', d.documentType)}</span>
      <Button disabled={busy || confirmation !== null} onClick={() => choose(d)}>{t('fleetRenewal.renew')}</Button>
    </div>)}
    {current && !loading && renewable(current) && <form className="space-y-3" onSubmit={e => {
      e.preventDefault()
      if (busyRef.current || confirmation) return
      const today = todayInTirana()
      if (!file || !expires || expires < today || (issued && (issued > today || issued > expires)) || number.length > 100) { setValidation(true); return }
      const body = new FormData(); body.set('file', file); body.set('expiresAt', expires)
      if (issued) body.set('issuedAt', issued)
      if (number.trim()) body.set('documentNumber', number.trim())
      setValidation(false); setConfirmation({ id: current.id, body, filename: file.name, expires })
    }}>
      <h4 className="font-medium">{label('documentType', current.documentType)}</h4>
      {validation && <p role="alert">{t('fleetRenewal.validation')}</p>}
      <label className="block text-sm">{t('fleetRenewal.file')}
        <input aria-label={t('fleetRenewal.file')} key={fileKey} type="file" accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png"
          disabled={busy || confirmation !== null} className="block w-full min-w-0 text-sm" onChange={e => {
            const chosen = e.target.files?.[0] ?? null
            const valid = chosen && chosen.size > 0 && chosen.size <= 5 * 1024 * 1024 && /\.(pdf|jpe?g|png)$/i.test(chosen.name)
              && (!chosen.type || ['application/pdf','image/jpeg','image/png'].includes(chosen.type))
            setFile(valid ? chosen : null); setValidation(!valid)
          }} />
      </label>
      <label className="block text-sm">{t('partner.documentNumber')}<Input aria-label={t('fleetRenewal.number')} value={number} maxLength={100} disabled={busy || confirmation !== null} onChange={e => setNumber(e.target.value)} /></label>
      <label className="block text-sm">{t('partner.issuedAt')}<Input aria-label={t('fleetRenewal.issued')} type="date" value={issued} disabled={busy || confirmation !== null} onChange={e => setIssued(e.target.value)} /></label>
      <label className="block text-sm">{t('partner.expiresAt')}<Input aria-label={t('fleetRenewal.expires')} type="date" required value={expires} disabled={busy || confirmation !== null} onChange={e => setExpires(e.target.value)} /></label>
      <div className="flex flex-wrap gap-2"><Button type="submit" disabled={busy || confirmation !== null}>{t('fleetRenewal.submit')}</Button>
        <Button type="button" disabled={busy || confirmation !== null} onClick={reset}>{t('common.cancel')}</Button></div>
    </form>}
    <Button disabled={busy || confirmation !== null} onClick={() => setShowHistory(v => !v)}>{t(showHistory ? 'fleetRenewal.hideHistory' : 'fleetRenewal.history')}</Button>
    {showHistory && <div className="space-y-2">
      {history.isFetching && <p>{t('fleetRenewal.loading')}</p>}
      {history.isError && <div><p role="alert">{t('fleetRenewal.historyError')}</p><Button disabled={busy} onClick={() => { void history.refetch() }}>{t('fleetRenewal.retry')}</Button></div>}
      {history.isSuccess && !history.isFetching && <ul className="space-y-2">{history.data.map(d => <li key={d.id} className="space-y-1 break-words rounded border border-ink-200 p-2">
        <p>{label('documentType', d.documentType)} · {d.documentNumber || '—'}</p>
        <StatusBadge status={d.verificationStatus} namespace="documentStatus" />
        <p className="text-sm">{t('partner.expiresAt')}: {d.expiresAt || '—'}</p>
        {d.rejectionReason && <p className="text-sm">{d.rejectionReason}</p>}
        <Button disabled={busy || confirmation !== null} onClick={() => { void download(d.id) }}>{t('fleetRenewal.download')}</Button>
      </li>)}</ul>}
    </div>}
    <ConfirmDialog open={confirmation !== null} title={t('fleetRenewal.confirmTitle')}
      description={<div className="break-words"><p>{confirmation?.filename} · {confirmation?.expires}</p><p>{t('fleetRenewal.confirmHint')}</p></div>}
      confirmLabel={t('fleetRenewal.submit')} loading={busy} onCancel={() => { if (!busyRef.current) setConfirmation(null) }} onConfirm={() => { void submit() }} />
  </section>
}
