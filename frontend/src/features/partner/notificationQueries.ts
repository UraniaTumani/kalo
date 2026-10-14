/** Invalidating the root refreshes the list and the unread badge together. */
export const partnerNotificationKeys = {
  root: (userId: number) => ['partner', 'notifications', userId] as const,
  unread: (userId: number) => [...partnerNotificationKeys.root(userId), 'unread-count'] as const,
  list: (userId: number, page: number) =>
    [...partnerNotificationKeys.root(userId), 'list', page] as const,
}
