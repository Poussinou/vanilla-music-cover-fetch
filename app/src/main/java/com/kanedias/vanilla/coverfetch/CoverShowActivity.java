/*
 * Copyright (C) 2017 Oleg Chernovskiy <adonai@xaker.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kanedias.vanilla.coverfetch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.v4.content.FileProvider;
import android.support.v4.provider.DocumentFile;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;

import com.kanedias.vanilla.plugins.DialogActivity;
import com.kanedias.vanilla.plugins.PluginConstants;
import com.kanedias.vanilla.plugins.PluginUtils;
import com.kanedias.vanilla.plugins.saf.SafRequestActivity;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.kanedias.vanilla.plugins.PluginConstants.*;
import static com.kanedias.vanilla.coverfetch.PluginService.pluginInstalled;
import static com.kanedias.vanilla.plugins.PluginUtils.*;
import static com.kanedias.vanilla.plugins.saf.SafUtils.findInDocumentTree;
import static com.kanedias.vanilla.plugins.saf.SafUtils.isSafNeeded;

/**
 * Main activity of Cover Fetch plugin. This will be presented as a dialog to the user
 * if one chooses it as the requested plugin.
 * <p/>
 *
 * @see PluginService service that launches this
 *
 * @author Oleg Chernovskiy
 */
public class CoverShowActivity extends DialogActivity {

    private static final int PICK_IMAGE_REQUEST = 1;

    private SharedPreferences mPrefs;

    private ImageView mCoverImage;
    private ViewSwitcher mSwitcher;
    private Button mOkButton, mWriteButton;
    private ProgressBar mProgressBar;
    private EditText mCustomSearch;
    private Button mCustomMedia;

    private CoverEngine mEngine = new CoverArchiveEngine();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cover_show);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mSwitcher = (ViewSwitcher) findViewById(R.id.loading_switcher);
        mCoverImage = (ImageView) findViewById(R.id.cover_image);
        mWriteButton = (Button) findViewById(R.id.write_button);
        mOkButton = (Button) findViewById(R.id.ok_button);
        mProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
        mCustomSearch = (EditText) findViewById(R.id.search_custom);
        mCustomMedia = (Button) findViewById(R.id.from_custom_media);

        setupUI();
        handlePassedIntent(true); // called in onCreate to be shown only once
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = new MenuInflater(this);
        inflater.inflate(R.menu.cover_options, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            switch (item.getItemId()) {
                case R.id.reload_option:
                case R.id.custom_option:
                    // show only when loading is complete
                    item.setVisible(mSwitcher.getDisplayedChild() == 1);
                    continue;
                default:
                    break;
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.custom_option:
                mCustomSearch.setVisibility(mCustomSearch.getVisibility() == VISIBLE ? GONE : VISIBLE);
                mCustomMedia.setVisibility(mCustomMedia.getVisibility() == VISIBLE ? GONE : VISIBLE);
                return true;
            case R.id.reload_option:
                mSwitcher.setDisplayedChild(0);
                handlePassedIntent(false);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void handlePassedIntent(boolean useLocal) {
        // check if this is an answer from tag plugin
        if (useLocal && TextUtils.equals(getIntent().getStringExtra(EXTRA_PARAM_P2P), P2P_READ_ART)) {
            // already checked this string in service, no need in additional checks
            if (loadFromTag()) {
                return;
            }
        }

        // we didn't receive artwork from tag plugin

        // try to retrieve from folder.jpg
        if (useLocal && loadFromFile()) {
            return;
        }

        // try to retrieve it via artwork engine
        String artist = getIntent().getStringExtra(EXTRA_PARAM_SONG_ARTIST);
        if (artist != null && artist.contains("No Artist")) {
            artist = null;
        }
        String album = getIntent().getStringExtra(EXTRA_PARAM_SONG_ALBUM);
        if (album != null && album.contains("No Album")) {
            album = null;
        }

        if (album != null && artist != null) {
            new ArtworkFetcher().execute(artist, album);
        } else if (album != null) {
            new ArtworkFetcher().execute(album);
        } else if (artist != null) {
            new ArtworkFetcher().execute(artist);
        } else {
            // album and artist both are null, take filename
            Uri fileUri = getIntent().getParcelableExtra(EXTRA_PARAM_URI);
            String fileName = fileUri.getLastPathSegment();
            int extensionStart = fileName.lastIndexOf(".");
            if (extensionStart > 0) {
                fileName = fileName.substring(0, extensionStart);
            }
            new ArtworkFetcher().execute(fileName);
        }
    }

    private boolean loadFromTag() {
        Uri imgLink = getIntent().getParcelableExtra(EXTRA_PARAM_P2P_VAL);
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(imgLink, "r");
            if (pfd == null) {
                return false;
            }

            Bitmap raw = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
            if (raw == null) {
                return false;
            }

            setCoverImage(raw);
            return true;
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, "Passed Uri points to invalid fd! " + imgLink, e);
        }
        return false;
    }

    private boolean loadFromFile() {
        if (!PluginUtils.havePermissions(this, WRITE_EXTERNAL_STORAGE)) {
            return false;
        }

        Uri fileUri = getIntent().getParcelableExtra(EXTRA_PARAM_URI);
        File media = new File(fileUri.getPath());
        File folderJpg = new File(media.getParentFile(), "folder.jpg");
        if (!folderJpg.exists()) {
            return false;
        }

        Bitmap raw = BitmapFactory.decodeFile(folderJpg.getPath());
        if (raw == null) {
            return false;
        }

        setCoverImage(raw);
        return true;
    }

    private void setCoverImage(Bitmap raw) {
        Drawable image;
        if (raw == null) {
            image = null;
            mWriteButton.setEnabled(false);
        } else {
            // we have some bitmap
            image = new BitmapDrawable(getResources(), raw);
            mWriteButton.setEnabled(true);
        }

        mCoverImage.setImageDrawable(image);
        mSwitcher.setDisplayedChild(1);
        invalidateOptionsMenu();
    }

    /**
     * Initialize UI elements with handlers and action listeners
     */
    private void setupUI() {
        mOkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mWriteButton.setOnClickListener(new SelectWriteAction());
        mCustomSearch.setOnEditorActionListener(new CustomSearchQueryListener());
        mCustomMedia.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, getString(R.string.pick_custom_media)), PICK_IMAGE_REQUEST);
            }
        });
    }

    /**
     * External artwork fetcher (using network). Operates asynchronously, notifies dialog when finishes.
     * On no result (no artwork, couldn't fetch etc.) shows toast about this, on success updates dialog text.
     */
    private class ArtworkFetcher extends AsyncTask<String, Void, byte[]> {
        @Override
        protected void onPreExecute() {
            mSwitcher.setDisplayedChild(0);
            mProgressBar.setVisibility(VISIBLE);
        }

        @Override
        protected byte[] doInBackground(String... params) {
            if (params.length == 1) {
                return mEngine.getCover(params[0]);
            }

            if (params.length == 2) {
                // artist, album
                return mEngine.getCover(params[0], params[1]);
            }

            return null;
        }

        @Override
        protected void onPostExecute(byte[] imgData) {
            mProgressBar.setVisibility(View.INVISIBLE);

            if(imgData == null || imgData.length == 0) {
                // no artwork - show excuse
                Toast.makeText(CoverShowActivity.this, R.string.cover_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap raw = BitmapFactory.decodeByteArray(imgData, 0, imgData.length);
            if (raw == null) {
                Toast.makeText(CoverShowActivity.this, R.string.invalid_cover_image_format, Toast.LENGTH_LONG).show();
            }
            setCoverImage(raw);
        }
    }

    /**
     * CLick listener for P2P integration, sends intent to write retrieved cover to local file tag
     */
    public void persistToFile() {
        // image must be present because this button enables only after it's downloaded
        Bitmap bitmap = ((BitmapDrawable) mCoverImage.getDrawable()).getBitmap();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream);
        byte[] imgData = stream.toByteArray();

        Uri uri = null;
        try {
            File coversDir = new File(getCacheDir(), "covers");
            if (!coversDir.exists() && !coversDir.mkdir()) {
                Log.e(LOG_TAG, "Couldn't create dir for covers! Path " + getCacheDir());
                return;
            }

            // cleanup old images
            for (File oldImg : coversDir.listFiles()) {
                if (!oldImg.delete()) {
                    Log.w(LOG_TAG, "Couldn't delete old image file! Path " + oldImg);
                }
            }

            // write artwork to file
            File coverTmpFile = new File(coversDir, UUID.randomUUID().toString());
            FileOutputStream fos = new FileOutputStream(coverTmpFile);
            fos.write(imgData);
            fos.close();

            // create sharable uri
            uri = FileProvider.getUriForFile(CoverShowActivity.this, "com.kanedias.vanilla.coverfetch.fileprovider", coverTmpFile);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Couldn't share private cover image file to tag editor!", e);
        } finally {
            Intent request = new Intent(ACTION_LAUNCH_PLUGIN);
            request.setPackage(PluginService.PLUGIN_TAG_EDIT_PKG);
            request.putExtra(EXTRA_PARAM_URI, getIntent().getParcelableExtra(EXTRA_PARAM_URI));
            request.putExtra(EXTRA_PARAM_PLUGIN_APP, getApplicationInfo());
            request.putExtra(EXTRA_PARAM_P2P, P2P_WRITE_ART);
            if (uri != null) { // artwork write succeeded
                grantUriPermission(PluginService.PLUGIN_TAG_EDIT_PKG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                request.putExtra(EXTRA_PARAM_P2P_VAL, uri);
            }
            startService(request);
        }
    }

    /**
     * Click listener for handling writing tag as folder.jpg
     */
    public void persistAsFolderJpg() {
        Uri fileUri = getIntent().getParcelableExtra(EXTRA_PARAM_URI);
        if (fileUri == null) {
            // wrong intent passed?
            return;
        }

        File mediaFile = new File(fileUri.getPath());
        if (!mediaFile.exists()) {
            // file deleted while launching intent or player db is not refreshed
            return;
        }

        // image must be present because this button enables only after it's downloaded
        Bitmap bitmap = ((BitmapDrawable) mCoverImage.getDrawable()).getBitmap();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        byte[] imgData = stream.toByteArray();

        File folderTarget = new File(mediaFile.getParent(), "folder.jpg");
        if (isSafNeeded(mediaFile)) {
            if (mPrefs.contains(PREF_SDCARD_URI)) {
                // we already got the permission!
                writeThroughSaf(imgData, mediaFile, folderTarget.getName());
                return;
            }

            // request SAF permissions in SAF activity
            Intent safIntent = new Intent(CoverShowActivity.this, SafRequestActivity.class);
            safIntent.putExtra(PluginConstants.EXTRA_PARAM_PLUGIN_APP, getApplicationInfo());
            safIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            safIntent.putExtras(getIntent());
            startActivity(safIntent);
            // it will pass us URI back after the work is done
        } else {
            writeThroughFile(imgData, mediaFile, folderTarget);
        }
    }




    /**
     * Write changes through SAF framework - the only way to do it in Android > 4.4 when working with SD card
     */
    private void writeThroughSaf(byte[] data, File original, String name) {
        DocumentFile originalRef;
        if (mPrefs.contains(PREF_SDCARD_URI)) {
            // no sorcery can allow you to gain URI to the document representing file you've been provided with
            // you have to find it again now using Document API

            // /storage/volume/Music/some.mp3 will become [storage, volume, music, some.mp3]
            List<String> pathSegments = new ArrayList<>(Arrays.asList(original.getAbsolutePath().split("/")));
            Uri allowedSdRoot = Uri.parse(mPrefs.getString(PREF_SDCARD_URI, ""));
            originalRef = findInDocumentTree(DocumentFile.fromTreeUri(this, allowedSdRoot), pathSegments);
        } else {
            // user will click the button again
            return;
        }

        if (originalRef == null) {
            // nothing selected or invalid file?
            Toast.makeText(this, R.string.saf_nothing_selected, Toast.LENGTH_LONG).show();
            return;
        }

        DocumentFile folderJpgRef = originalRef.getParentFile().createFile("image/*", name);
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(folderJpgRef.getUri(), "rw");
            if (pfd == null) {
                // should not happen
                Log.e(LOG_TAG, "SAF provided incorrect URI!" + folderJpgRef.getUri());
                return;
            }

            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(data);
            fos.close();

            // rescan original file
            MediaScannerConnection.scanFile(this, new String[]{original.getAbsolutePath()}, null, null);
            Toast.makeText(this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.saf_write_error) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(LOG_TAG, "Failed to write to file descriptor provided by SAF!", e);
        }
    }

    /**
     * Write through file-based API
     * @param data - data to write
     * @param original - original media file that was requested by user
     * @param target - target file for writing metadata into
     */
    private void writeThroughFile(byte[] data, File  original, File target) {
        try {
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(data);
            fos.close();

            // rescan original file
            MediaScannerConnection.scanFile(this, new String[]{original.getAbsolutePath()}, null, null);
            Toast.makeText(this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_writing_file) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(LOG_TAG, "Failed to write to file descriptor provided by SAF!", e);
        }
    }

    /**
     * This is requested in {@link SelectWriteAction#onClick(View)} case 0.
     *
     * Catch it back from {@link PluginUtils#checkAndRequestPermissions(Activity, String)} here.
     * If user declined our request, just do nothing. If not, continue processing and persist the file.
     *
     * @param requestCode request code that was entered in {@link Activity#requestPermissions(String[], int)}
     * @param permissions permission array that was entered in {@link Activity#requestPermissions(String[], int)}
     * @param grantResults results of permission request. Indexes of permissions array are linked with these
     *
     * @see SelectWriteAction
     * @see PluginUtils
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != PERMISSIONS_REQUEST_CODE) {
            return;
        }

        for (int i = 0; i < permissions.length; ++i) {
            if (TextUtils.equals(permissions[i], WRITE_EXTERNAL_STORAGE)
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                // continue persist process started in Write... -> folder.jpg
                persistAsFolderJpg();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case PICK_IMAGE_REQUEST: // custom image requested
                if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                    return;
                }

                try {
                    Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), data.getData());
                    mCoverImage.setImageBitmap(bitmap);
                } catch (IOException e) {
                    Toast.makeText(this, getString(R.string.error_decoding_image) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                    Log.e(LOG_TAG, "Failed to decode bitmap from passed intent image!", e);
                }
            default:
                super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Listener which invokes action dialog on click with selection on where to write the retrieved cover
     */
    private class SelectWriteAction implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            List<String> actions = new ArrayList<>();
            actions.add(getString(R.string.write_to_folder));

            // if tag editor is installed, show `write to tag` button
            if (pluginInstalled(CoverShowActivity.this, PluginService.PLUGIN_TAG_EDIT_PKG)) {
                actions.add(getString(R.string.write_to_file));
            }

            new AlertDialog.Builder(CoverShowActivity.this)
                    .setItems(actions.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            switch (which) {
                                case 0: // to folder
                                    // onResume will fire both on first launch and on return from permission request
                                    if (!checkAndRequestPermissions(CoverShowActivity.this, WRITE_EXTERNAL_STORAGE)) {
                                        return;
                                    }

                                    persistAsFolderJpg();
                                    break;
                                case 1: // to file
                                    persistToFile();
                                    break;
                            }
                        }
                    }).create().show();
        }
    }

    /**
     * Listener which submits query to the artwork execution engine
     */
    private class CustomSearchQueryListener implements TextView.OnEditorActionListener {

        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            new ArtworkFetcher().execute(v.getText().toString());
            return true;
        }
    }
}
