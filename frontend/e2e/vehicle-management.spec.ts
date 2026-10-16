import { test, expect, SEEDED, apiLogin, seedSession, gotoSettled, RENDER_TIMEOUT } from './support/fixtures'

/**
 * F19: edit and reactivate a real vehicle through the partner UI.
 *
 * Creates a dedicated test vehicle through the real API so seeded fleet
 * assignments are untouched. No API mocking, no deletion, no DB resets.
 */
test('F19 · partner edits an inactive vehicle and reactivates it', async ({
  page,
  context,
  app,
  guards,
}) => {
  void app
  void guards
  test.setTimeout(180_000)

  const tokens = await apiLogin(page.request, SEEDED.partner.phone, SEEDED.partner.password)
  await seedSession(context, tokens)
  const headers = { Authorization: `Bearer ${tokens.accessToken}` }
  const plate = `FT${Date.now().toString().slice(-8)}`
  const vehicleData = {
    plateNumber: plate,
    brand: 'Skoda',
    model: 'Octavia',
    manufactureYear: 2024,
    seats: 4,
    vehicleType: 'STANDARD',
    registrationExpiryDate: '2030-12-31',
    insuranceExpiryDate: '2030-12-31',
    technicalInspectionExpiryDate: '2030-12-31',
  }

  const created = await page.request.post('/api/v1/partner/vehicles', {
    headers,
    data: vehicleData,
  })
  expect(created.status(), await created.text()).toBe(201)
  const vehicle = await created.json()

  await gotoSettled(page, '/partner/vehicles')
  await expect(page.getByText(plate, { exact: true })).toBeVisible({ timeout: RENDER_TIMEOUT })

  // The deactivation must remain reversible, unlike the old confirmation copy.
  const row = page.locator('tr').filter({ hasText: plate })
  await row.getByRole('button', { name: 'Deactivate' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText(/reactivate the vehicle later/i)

  const deactivatedResponse = page.waitForResponse(
    (r) => r.url().endsWith(`/api/v1/partner/vehicles/${vehicle.id}`) && r.request().method() === 'DELETE',
  )
  await dialog.getByRole('button', { name: /deactivate vehicle/i }).click()
  expect((await deactivatedResponse).status()).toBe(204)

  await expect(row).toContainText('Inactive', { timeout: RENDER_TIMEOUT })

  // Editing the inactive vehicle must not silently change its status.
  await row.getByRole('button', { name: `Edit ${plate}` }).click()
  await expect(page.getByText(`Edit ${plate}`)).toBeVisible()
  await page.getByLabel('Model', { exact: true }).fill('Superb')
  await page.getByLabel('Seats', { exact: true }).last().fill('5')

  const updatedResponse = page.waitForResponse(
    (r) => r.url().endsWith(`/api/v1/partner/vehicles/${vehicle.id}`) && r.request().method() === 'PUT',
  )
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  expect((await updatedResponse).status()).toBe(200)

  const edited = await page.request.get(`/api/v1/partner/vehicles/${vehicle.id}`, { headers })
  expect(edited.status()).toBe(200)
  expect(await edited.json()).toMatchObject({ model: 'Superb', seats: 5, status: 'INACTIVE' })

  await row.getByRole('button', { name: 'Reactivate' }).click()
  await expect(dialog).toContainText(/paperwork must be valid/i)

  const activatedResponse = page.waitForResponse(
    (r) => r.url().endsWith(`/api/v1/partner/vehicles/${vehicle.id}`) && r.request().method() === 'PUT',
  )
  await dialog.getByRole('button', { name: 'Reactivate', exact: true }).click()
  expect((await activatedResponse).status()).toBe(200)

  const active = await page.request.get(`/api/v1/partner/vehicles/${vehicle.id}`, { headers })
  expect(active.status()).toBe(200)
  expect(await active.json()).toMatchObject({ model: 'Superb', seats: 5, status: 'ACTIVE' })
})
