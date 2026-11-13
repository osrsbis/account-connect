package com.osrsbestinslot.export;

import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * One multipart frames POST to /store-frames-ingest and how its answer is read. Store-clip chunks and drop
 * segments share the request and the answer rules; each caller keeps its own gates, retry ladder and accounting.
 */
final class FramesUpload
{
	private FramesUpload()
	{
	}

	interface Answer
	{
		/** A 2xx. refused: the body says "dropped" (not staff, clips off), a deliberate refusal never retried. */
		void stored(boolean refused);

		/** A network failure, a 429 or a 5xx: retryable. retryAfterMs is the 429's Retry-After, else 0. */
		void retry(String why, long retryAfterMs);

		/** Any other 4xx: it fails identically on every attempt. A 403 is a revoked link. */
		void rejected(int code);
	}

	/** Build and enqueue one attempt on the upload client (its timeouts are sized to the payload). */
	static void post(OkHttpClient client, String base, MultipartBody body, String account, Answer answer)
	{
		Request request = new Request.Builder()
			.url(base + "/store-frames-ingest")
			.post(ServerGrants.withMediaAccount(body, account))
			.build();
		StoreClips.uploadClient(client).newCall(request).enqueue(EventQueue.answered(
			e -> answer.retry(e == null ? "io" : String.valueOf(e.getMessage()), 0L),
			response ->
			{
				int code = response.code();
				if (response.isSuccessful())
				{
					answer.stored(DropProof.dropSegmentWasRefused(response));
				}
				else if (code == 429 || code >= 500)
				{
					Long ra = code == 429 ? SnapshotUploader.parseRetryAfterSeconds(response.header("Retry-After")) : null;
					answer.retry("http " + code, ra == null ? 0L : ra * 1000L);
				}
				else
				{
					answer.rejected(code);
				}
			}));
	}
}
