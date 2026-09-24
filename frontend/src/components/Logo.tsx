import logo from '@/assets/mr-taxi-logo.png'

/**
 * The MR TAXI lockup.
 *
 * Always on a dark plate, and not as a stylistic preference: half the wordmark
 * is white, so on the app's own near-white surfaces "mr" would simply
 * disappear and the brand would read as "taxi". The artwork carries its own
 * black field, so the plate is the same black and the two meet invisibly.
 *
 * Sized by height only. The image keeps its own aspect ratio — a logo squeezed
 * to fit a box is a different logo.
 */
export function Logo({
  className = 'h-7',
  withPlate = true,
}: {
  /** Height utility; width follows from the artwork. */
  className?: string
  /** Off only where the surface behind it is already dark. */
  withPlate?: boolean
}) {
  const image = (
    <img
      src={logo}
      alt="MR TAXI"
      className={`${className} w-auto select-none`}
      draggable={false}
    />
  )

  if (!withPlate) return image

  return (
    <span className="inline-flex items-center rounded-lg bg-black px-2 py-1.5">{image}</span>
  )
}
