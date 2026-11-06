# Backend, free or super cheap

Pick, in order:
1. Supabase Free, $0: database, auth, storage, edge functions. 50,000 users, 500 MB database, 1 GB storage. Pauses after 1 week idle.
2. Firebase Spark, $0: Firestore 1 GiB, 50,000 reads per day, 20,000 writes per day. Storage now needs Blaze, pay as you go.
3. Cloudflare Workers Free, $0: 100,000 requests per day, never sleeps. Best for the simple API.
4. Render Free, $0 for testing: sleeps after 15 minutes, $7 per month for always on.

Start with JSON files in this repo for v0, then Supabase Free for v1.
