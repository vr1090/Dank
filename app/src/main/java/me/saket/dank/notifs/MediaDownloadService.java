package me.saket.dank.notifs;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.NotificationCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import me.saket.dank.R;
import me.saket.dank.data.MediaLink;
import me.saket.dank.ui.media.MediaDownloadJob;
import me.saket.dank.utils.Intents;
import me.saket.dank.utils.Strings;
import me.saket.dank.utils.glide.GlideProgressTarget;
import timber.log.Timber;

/**
 * Downloads images and videos to disk.
 */
public class MediaDownloadService extends Service {

  private static final String KEY_MEDIA_LINK_TO_DOWNLOAD = "mediaLinkToDownload";
  private static final int MAX_LENGTH_FOR_NOTIFICATION_TITLE = 40;
  private static final String REQUESTCODE_RETRY_DOWNLOAD_PREFIX_ = "300";
  private static final String REQUESTCODE_SHARE_IMAGE_PREFIX_ = "301";
  private static final String REQUESTCODE_DELETE_IMAGE_PREFIX_ = "302";
  private static final String REQUESTCODE_OPEN_IMAGE_PREFIX_ = "303";

  private final Set<String> ongoingDownloadUrls = new HashSet<>();
  private final Relay<MediaLink> mediaLinksToDownloadStream = PublishRelay.create();
  private CompositeDisposable disposables = new CompositeDisposable();

  private final Map<String, MediaDownloadJob> downloadJobs = new HashMap<>();
  private final Relay<Collection<MediaDownloadJob>> downloadJobStream = PublishRelay.create();

  public static void enqueueDownload(Context context, MediaLink mediaLink) {
    Intent intent = new Intent(context, MediaDownloadService.class);
    intent.putExtra(KEY_MEDIA_LINK_TO_DOWNLOAD, mediaLink);
    context.startService(intent);
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();

    // Create a summary notification + stop service when all downloads finish.
    disposables.add(
        downloadJobStream.subscribe(downloadJobs -> {
          boolean allMediaDownloaded = true;
          for (MediaDownloadJob downloadJob : downloadJobs) {
            if (downloadJob.progressState() != MediaDownloadJob.ProgressState.DOWNLOADED) {
              allMediaDownloaded = false;
            }
          }

          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Notification bundleSummaryNotification = createOrUpdateBundleSummaryNotification(downloadJobs, allMediaDownloaded);
            NotificationManagerCompat.from(this).notify(NotificationConstants.MEDIA_DOWNLOAD_BUNDLE_SUMMARY, bundleSummaryNotification);
          }

          if (allMediaDownloaded) {
            stopSelf();
          }
        })
    );

    disposables.add(
        mediaLinksToDownloadStream
            .flatMap(mediaLink -> downloadImage(mediaLink)
                .doOnComplete(() -> ongoingDownloadUrls.remove(mediaLink.originalUrl()))
            )
            // Starting from Nougat, Android has a rate limiter in place which puts a certain
            // threshold between every update. The exact value is somewhere between 100ms to 200ms.
            .sample(200, TimeUnit.MILLISECONDS, true)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(downloadJob -> {
              int notificationId = createNotificationIdFor(downloadJob);

              switch (downloadJob.progressState()) {
                case CONNECTING:
                  updateIndividualProgressNotification(downloadJob, notificationId);
                  break;

                case IN_FLIGHT:
                  updateIndividualProgressNotification(downloadJob, notificationId);
                  break;

                case FAILED:
                  displayErrorNotification(downloadJob, notificationId);
                  ongoingDownloadUrls.remove(downloadJob.mediaLink().originalUrl());
                  break;

                case DOWNLOADED:
                  displaySuccessNotification(downloadJob, notificationId);
                  ongoingDownloadUrls.remove(downloadJob.mediaLink().originalUrl());
                  break;
              }

              downloadJobs.put(downloadJob.mediaLink().originalUrl(), downloadJob);
              downloadJobStream.accept(downloadJobs.values());
            })
    );
  }

  @Override
  public void onDestroy() {
    disposables.clear();
    super.onDestroy();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    MediaLink mediaLinkToDownload = (MediaLink) intent.getSerializableExtra(KEY_MEDIA_LINK_TO_DOWNLOAD);
    boolean duplicateFound = ongoingDownloadUrls.contains(mediaLinkToDownload.originalUrl());

    // TODO: Remove.
    if (duplicateFound) {
      int notificationId = (NotificationConstants.MEDIA_DOWNLOAD_PROGRESS_PREFIX_ + mediaLinkToDownload.originalUrl()).hashCode();
      NotificationManagerCompat.from(this).cancel(notificationId);
      duplicateFound = false;
    }

    if (!duplicateFound) {
      ongoingDownloadUrls.add(mediaLinkToDownload.originalUrl());
      mediaLinksToDownloadStream.accept(mediaLinkToDownload);
    }
    return START_NOT_STICKY;
  }

  @TargetApi(Build.VERSION_CODES.N)
  private Notification createOrUpdateBundleSummaryNotification(Collection<MediaDownloadJob> downloadJobs, boolean isCancelable) {
    return new NotificationCompat.Builder(this)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setGroup(NotificationConstants.MEDIA_DOWNLOAD_BUNDLE_NOTIFS_GROUP_KEY)
        .setGroupSummary(true)
        .setShowWhen(true)
        .setColor(ContextCompat.getColor(this, R.color.notification_icon_color))
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setOnlyAlertOnce(true)
        .setWhen(0)
        .setOngoing(!isCancelable)
        .build();
  }

  private void updateIndividualProgressNotification(MediaDownloadJob mediaDownloadJob, int notificationId) {
    String notificationTitle = ellipsizeNotifTitleIfExceedsMaxLength(
        getString(R.string.mediaalbumviewer_download_notification_title, mediaDownloadJob.mediaLink().originalUrl())
    );
    boolean indeterminateProgress = mediaDownloadJob.progressState() == MediaDownloadJob.ProgressState.CONNECTING;

    Notification notification = new NotificationCompat.Builder(this)
        .setContentTitle(notificationTitle)
        .setContentText(mediaDownloadJob.downloadProgress() + "%")
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setGroup(NotificationConstants.MEDIA_DOWNLOAD_BUNDLE_NOTIFS_GROUP_KEY)
        .setLocalOnly(true)   // Hide from wearables.
        .setWhen(0)
        .setColor(ContextCompat.getColor(this, R.color.notification_icon_color))
        .setProgress(100 /* max */, mediaDownloadJob.downloadProgress(), indeterminateProgress)
        .build();

    NotificationManagerCompat.from(this).notify(notificationId, notification);
  }

  /**
   * We're ellipsizing the title so that the notification's content text (which is the progress
   * percentage at the time of writing this) is always visible.
   */
  private static String ellipsizeNotifTitleIfExceedsMaxLength(String fullTitle) {
    return fullTitle.length() > MAX_LENGTH_FOR_NOTIFICATION_TITLE
        ? Strings.safeSubstring(fullTitle, MAX_LENGTH_FOR_NOTIFICATION_TITLE) + "…"
        : fullTitle;
  }

  private void displayErrorNotification(MediaDownloadJob failedDownloadJob, int notificationId) {
    Intent retryIntent = MediaNotifActionReceiver.createRetryDownloadIntent(this, failedDownloadJob);
    PendingIntent retryPendingIntent = PendingIntent.getBroadcast(this,
        (int) Long.parseLong(REQUESTCODE_RETRY_DOWNLOAD_PREFIX_ + notificationId),
        retryIntent,
        PendingIntent.FLAG_UPDATE_CURRENT
    );

    Notification errorNotification = new NotificationCompat.Builder(this)
        .setContentTitle(getString(
            failedDownloadJob.mediaLink().isVideo()
                ? R.string.mediadownloadnotification_failed_to_save_video
                : R.string.mediadownloadnotification_failed_to_save_image
        ))
        .setContentText(getString(R.string.mediadownloadnotification_tap_to_retry_url, failedDownloadJob.mediaLink().originalUrl()))
        .setSmallIcon(R.drawable.ic_error_24dp)
        .setOngoing(false)
        .setGroup(NotificationConstants.MEDIA_DOWNLOAD_BUNDLE_NOTIFS_GROUP_KEY)
        .setLocalOnly(true)   // Hide from wearables.
        .setWhen(0) // TODO: set this.
        .setColor(ContextCompat.getColor(this, R.color.notification_icon_color))
        .setContentIntent(retryPendingIntent)
        .setAutoCancel(false)
        .build();
    NotificationManagerCompat.from(this).notify(notificationId, errorNotification);
  }

  private void displaySuccessNotification(MediaDownloadJob completedDownloadJob, int notificationId) {
    // Content intent.
    Uri imageContentUri = FileProvider.getUriForFile(this, getString(R.string.file_provider_authority), completedDownloadJob.downloadedFile());
    PendingIntent viewImagePendingIntent = PendingIntent.getActivity(this,
        (int) Long.parseLong(REQUESTCODE_OPEN_IMAGE_PREFIX_ + notificationId),
        Intents.createForViewingImage(this, imageContentUri),
        PendingIntent.FLAG_CANCEL_CURRENT
    );

    // Share action.
    PendingIntent shareImagePendingIntent = PendingIntent.getBroadcast(this,
        (int) Long.parseLong(REQUESTCODE_SHARE_IMAGE_PREFIX_ + notificationId),
        MediaNotifActionReceiver.createShareImageIntent(this, completedDownloadJob),
        PendingIntent.FLAG_CANCEL_CURRENT
    );
    NotificationCompat.Action shareImageAction = new NotificationCompat.Action(0,
        getString(R.string.mediaalbumviewer_download_notification_share),
        shareImagePendingIntent
    );

    // Delete action.
    PendingIntent deleteImagePendingIntent = PendingIntent.getBroadcast(this,
        (int) Long.parseLong(REQUESTCODE_DELETE_IMAGE_PREFIX_ + notificationId),
        MediaNotifActionReceiver.createDeleteImageIntent(this, completedDownloadJob),
        PendingIntent.FLAG_CANCEL_CURRENT
    );
    NotificationCompat.Action deleteImageAction = new NotificationCompat.Action(0,
        getString(R.string.mediaalbumviewer_download_notification_delete),
        deleteImagePendingIntent
    );

    Glide.with(this)
        .asBitmap()
        .load(Uri.fromFile(completedDownloadJob.downloadedFile()))
        .into(new SimpleTarget<Bitmap>() {
          @Override
          public void onResourceReady(Bitmap imageBitmap, Transition<? super Bitmap> transition) {
            Notification successNotification = new NotificationCompat.Builder(MediaDownloadService.this)
                .setContentTitle(getString(
                    completedDownloadJob.mediaLink().isVideo()
                        ? R.string.mediadownloadnotification_video_saved
                        : R.string.mediadownloadnotification_image_saved
                ))
                .setContentText(completedDownloadJob.mediaLink().originalUrl())
                .setSmallIcon(R.drawable.ic_cloud_download_24dp)
                .setOngoing(false)
                .setGroup(NotificationConstants.MEDIA_DOWNLOAD_BUNDLE_NOTIFS_GROUP_KEY)
                .setLocalOnly(true)
                .setWhen(System.currentTimeMillis())
                .setColor(ContextCompat.getColor(MediaDownloadService.this, R.color.notification_icon_color))
                .setContentIntent(viewImagePendingIntent)
                .addAction(shareImageAction)
                .addAction(deleteImageAction)
                .setAutoCancel(true)
                .setStyle(new NotificationCompat.BigPictureStyle().bigPicture(imageBitmap))
                .build();

            Timber.i("Displaying success notif");
            NotificationManagerCompat.from(MediaDownloadService.this).notify(notificationId, successNotification);
          }
        });
  }

  // TODO: Remove random url.
  private Observable<MediaDownloadJob> downloadImage(MediaLink mediaLink) {
    String imageUrl = mediaLink.originalUrl()
        //+ "?" + String.valueOf(System.currentTimeMillis())
        ;

    return Observable.create(emitter -> {
      Target<File> fileFutureTarget = new SimpleTarget<File>() {
        @Override
        public void onResourceReady(File downloadedFile, Transition<? super File> transition) {
          emitter.onNext(MediaDownloadJob.createProgress(mediaLink, 100));
          emitter.onNext(MediaDownloadJob.createDownloaded(mediaLink, downloadedFile));
          emitter.onComplete();
        }

        @Override
        public void onLoadFailed(@Nullable Drawable errorDrawable) {
          emitter.onNext(MediaDownloadJob.createFailed(mediaLink));
          emitter.onComplete();
        }
      };

      GlideProgressTarget<String, File> progressTarget = new GlideProgressTarget<String, File>(fileFutureTarget) {
        @Override
        public float getGranularityPercentage() {
          return 2f;
        }

        @Override
        protected void onConnecting() {
          emitter.onNext(MediaDownloadJob.createConnecting(mediaLink));
        }

        @Override
        protected void onDownloading(long bytesRead, long expectedLength) {
          int progress = (int) (100 * (float) bytesRead / expectedLength);
          emitter.onNext(MediaDownloadJob.createProgress(mediaLink, progress));
        }

        @Override
        protected void onDownloaded() {}

        @Override
        protected void onDelivered() {}
      };
      progressTarget.setModel(this, imageUrl);

      Glide.with(this).download(imageUrl).into(progressTarget);
      emitter.setCancellable(() -> Glide.with(this).clear(progressTarget));
    });
  }

  public static int createNotificationIdFor(MediaDownloadJob downloadJob) {
    return (NotificationConstants.MEDIA_DOWNLOAD_PROGRESS_PREFIX_ + downloadJob.mediaLink().originalUrl()).hashCode();
  }
}