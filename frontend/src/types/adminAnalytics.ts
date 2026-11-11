export interface AdminAnalytics {
  days: number
  startDate: string
  endDate: string
  timezone: string
  generatedAt: string
  userId: number | null
  daily: { date: string; succeeded: number; failed: number; generatingUsers: number }[]
  taskStates: { status: string; count: number }[]
  ranking: { id: number; name: string; images: number }[]
  users: { id: number; name: string }[]
  succeeded: number
  failed: number
  activeUsers: number
  successRate: number | null
}
