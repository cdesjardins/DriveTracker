/*
 * Copyright (c) 2010 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.api.client.sample.calendar.android;

import com.google.api.client.extensions.android2.AndroidHttp;
import com.google.api.client.googleapis.GoogleHeaders;
import com.google.api.client.googleapis.extensions.android2.auth.GoogleAccountManager;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.sample.calendar.android.MyLocation.LocationResult;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.CalendarClient;
import com.google.api.services.calendar.CalendarRequestInitializer;
import com.google.api.services.calendar.CalendarUrl;
import com.google.api.services.calendar.model.CalendarEntry;
import com.google.api.services.calendar.model.CalendarFeed;
import com.google.api.services.calendar.model.EventEntry;
import com.google.api.services.calendar.model.When;
import com.google.common.collect.Lists;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.Toast;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sample for Google Calendar Data API using the Atom wire format. It shows how
 * to authenticate, get calendars, add a new calendar, update it, and delete it.
 * <p>
 * To enable logging of HTTP requests/responses, change {@link #LOGGING_LEVEL}
 * to {@link Level#CONFIG} or {@link Level#ALL} and run this command:
 * </p>
 * 
 * <pre>
 * adb shell setprop log.tag.HttpTransport DEBUG
 * </pre>
 * 
 * @author Yaniv Inbar
 */
public final class CalendarSample extends Activity
{

    /** Logging level for HTTP requests/responses. */
    private static Level LOGGING_LEVEL = Level.CONFIG;

    private static final String AUTH_TOKEN_TYPE = "cl";

    private static final String TAG = "CalendarSample";

    private static final int MENU_ADD = 0;

    private static final int MENU_ACCOUNTS = 1;

    private static final int CONTEXT_EDIT = 0;

    private static final int CONTEXT_DELETE = 1;

    private static final int REQUEST_AUTHENTICATE = 0;

    CalendarClient client;

    private CalendarEntry driveCalendar;

    final HttpTransport transport = AndroidHttp.newCompatibleTransport();

    String accountName;

    static final String PREF = TAG;
    static final String PREF_ACCOUNT_NAME = "accountName";
    static final String PREF_AUTH_TOKEN = "authToken";
    static final String PREF_GSESSIONID = "gsessionid";
    GoogleAccountManager accountManager;
    SharedPreferences settings;
    CalendarAndroidRequestInitializer requestInitializer;
    DateTime driveDate;
    int nKilometers;
    String myAddress;
    ProgressDialog progressDialog;
    Handler progressHandler;

    public class CalendarAndroidRequestInitializer extends
            CalendarRequestInitializer
    {

        String authToken;

        public CalendarAndroidRequestInitializer()
        {
            super(transport);
            authToken = settings.getString(PREF_AUTH_TOKEN, null);
            setGsessionid(settings.getString(PREF_GSESSIONID, null));
        }

        @Override
        public void intercept(HttpRequest request) throws IOException
        {
            super.intercept(request);
            request.getHeaders().setAuthorization(
                    GoogleHeaders.getGoogleLoginValue(authToken));
        }

        @Override
        public boolean handleResponse(HttpRequest request,
                HttpResponse response, boolean retrySupported)
                throws IOException
        {
            switch (response.getStatusCode())
            {
            case 302:
                super.handleResponse(request, response, retrySupported);
                SharedPreferences.Editor editor = settings.edit();
                editor.putString(PREF_GSESSIONID, getGsessionid());
                editor.commit();
                return true;
            case 401:
                accountManager.invalidateAuthToken(authToken);
                authToken = null;
                SharedPreferences.Editor editor2 = settings.edit();
                editor2.remove(PREF_AUTH_TOKEN);
                editor2.commit();
                return false;
            }
            return false;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Logger.getLogger("com.google.api.client").setLevel(LOGGING_LEVEL);
        accountManager = new GoogleAccountManager(this);
        settings = this.getSharedPreferences(PREF, 0);
        requestInitializer = new CalendarAndroidRequestInitializer();
        client = new CalendarClient(requestInitializer.createRequestFactory());
        client.setPrettyPrint(true);
        client.setApplicationName("Google-CalendarAndroidSample/1.0");
        setContentView(R.layout.linearlayout);
        addOKButtonListener();
    }

    private void addOKButtonListener()
    {
        Button OKButton = (Button) findViewById(R.id.OKButton);
        OKButton.setOnClickListener(new OnClickListener()
        {
            // @Override
            public void onClick(View v)
            {
                Date date = new Date();

                DatePicker datePicker = (DatePicker) findViewById(R.id.datePicker);
                EditText kilometerEdit = (EditText) findViewById(R.id.kilometerEdit);
                date.setDate(datePicker.getDayOfMonth());
                date.setMonth(datePicker.getMonth());
                date.setYear(datePicker.getYear() - 1900);
                String sKilometers;

                sKilometers = kilometerEdit.getText().toString();
                if (sKilometers.length() > 0)
                {
                    nKilometers = Integer.parseInt(sKilometers);
                    driveDate = new DateTime(date);
                    Log.i("CJD1", "OK Button: " + nKilometers + " " + driveDate);
                    progressDialog = ProgressDialog.show(CalendarSample.this,
                            "", "Getting calendar, and GPS", true);

                    progressHandler = new Handler()
                    {
                        @Override
                        public void handleMessage(Message msg)
                        {
                            progressDialog.dismiss();
                        }
                    };
                    Thread checkUpdate = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            getCalendarAccount();
                        }
                    };
                    checkUpdate.start();

                } else
                {
                    Toast toast = Toast.makeText(getApplicationContext(),
                            "Kilometers must be filled in", 1000);
                    toast.show();
                }
            }
        });
    }

    void TrackDrive()
    {
        try
        {
            CalendarUrl url = new CalendarUrl(driveCalendar.getEventFeedLink());
            EventEntry event = new EventEntry();
            event.title = nKilometers + " km, " + myAddress;
            When when = new When();
            when.startTime = driveDate;
            event.when = when;
            EventEntry result = client.eventFeed().insert().execute(url, event);
            Toast toast = Toast.makeText(getApplicationContext(),
                    "Created entry:" + event.title, 2000);
            toast.show();
            CalendarSample.this.finish();

        } catch (Exception e)
        {
            Log.e("CJD", "TrackDrive Error: " + e.toString());
            Toast toast = Toast.makeText(getApplicationContext(), e.toString(),
                    5000);
            toast.show();
        } finally
        {
            progressHandler.sendEmptyMessage(0);
        }
    }

    void setAuthToken(String authToken)
    {
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(PREF_AUTH_TOKEN, authToken);
        editor.commit();
        requestInitializer.authToken = authToken;
    }

    void setAccountName(String accountName)
    {
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(PREF_ACCOUNT_NAME, accountName);
        editor.remove(PREF_GSESSIONID);
        editor.commit();
        this.accountName = accountName;
        requestInitializer.setGsessionid(null);
    }

    MyLocation myLocation = new MyLocation();

    public LocationResult locationResult = new LocationResult()
    {
        @Override
        public void gotLocation(final Location loc)
        {
            Geocoder geocoder = new Geocoder(CalendarSample.this,
                    Locale.ENGLISH);
            try
            {
                List<Address> addresses = geocoder.getFromLocation(
                        loc.getLatitude(), loc.getLongitude(), 1);

                if (addresses != null)
                {
                    Address returnedAddress = addresses.get(0);
                    StringBuilder strReturnedAddress = new StringBuilder();
                    for (int i = 0; i < returnedAddress
                            .getMaxAddressLineIndex(); i++)
                    {
                        strReturnedAddress.append(
                                returnedAddress.getAddressLine(i)).append(", ");
                    }
                    myAddress = strReturnedAddress.toString();
                    TrackDrive();

                } else
                {
                    myAddress = "No Address returned!";
                    progressHandler.sendEmptyMessage(0);
                }
            } catch (IOException e)
            {
                e.printStackTrace();
                myAddress = "Canont get Address!";
                progressHandler.sendEmptyMessage(0);
            }
        }
    };

    private void getAddress()
    {
        myLocation.getLocation(this, locationResult);
    }

    private void getCalendarAccount()
    {
        Account account = accountManager.getAccountByName(accountName);
        if (account != null)
        {
            // handle invalid token
            if (requestInitializer.authToken == null)
            {
                accountManager.manager.getAuthToken(account, AUTH_TOKEN_TYPE,
                        true, new AccountManagerCallback<Bundle>()
                        {
                            public void run(AccountManagerFuture<Bundle> future)
                            {
                                try
                                {
                                    Bundle bundle = future.getResult();
                                    if (bundle
                                            .containsKey(AccountManager.KEY_INTENT))
                                    {
                                        Intent intent = bundle
                                                .getParcelable(AccountManager.KEY_INTENT);
                                        int flags = intent.getFlags();
                                        flags &= ~Intent.FLAG_ACTIVITY_NEW_TASK;
                                        intent.setFlags(flags);
                                        startActivityForResult(intent,
                                                REQUEST_AUTHENTICATE);
                                    } else if (bundle
                                            .containsKey(AccountManager.KEY_AUTHTOKEN))
                                    {
                                        setAuthToken(bundle
                                                .getString(AccountManager.KEY_AUTHTOKEN));
                                        executeRefreshCalendars();
                                    }
                                } catch (Exception e)
                                {
                                    handleException(e);
                                }
                            }
                        }, null);
            } else
            {
                executeRefreshCalendars();
            }
            return;
        }
        chooseAccount();
    }

    private void chooseAccount()
    {
        accountManager.manager.getAuthTokenByFeatures(
                GoogleAccountManager.ACCOUNT_TYPE, AUTH_TOKEN_TYPE, null,
                CalendarSample.this, null, null,
                new AccountManagerCallback<Bundle>()
                {

                    public void run(AccountManagerFuture<Bundle> future)
                    {
                        Bundle bundle;
                        try
                        {
                            bundle = future.getResult();
                            setAccountName(bundle
                                    .getString(AccountManager.KEY_ACCOUNT_NAME));
                            setAuthToken(bundle
                                    .getString(AccountManager.KEY_AUTHTOKEN));
                            executeRefreshCalendars();
                        } catch (OperationCanceledException e)
                        {
                            // user canceled
                        } catch (AuthenticatorException e)
                        {
                            handleException(e);
                        } catch (IOException e)
                        {
                            handleException(e);
                        }
                    }
                }, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode)
        {
        case REQUEST_AUTHENTICATE:
            if (resultCode == RESULT_OK)
            {
                getCalendarAccount();
            } else
            {
                chooseAccount();
            }
            break;
        }
    }

    void executeRefreshCalendars()
    {
        List<CalendarEntry> calendars = Lists.newArrayList();
        calendars.clear();
        try
        {
            CalendarUrl url = forAllCalendarsFeed();
            // page through results
            while (true)
            {
                CalendarFeed feed = client.calendarFeed().list().execute(url);
                if (feed.calendars != null)
                {
                    calendars.addAll(feed.calendars);
                }
                String nextLink = feed.getNextLink();
                if (nextLink == null)
                {
                    break;
                }
            }
            int numCalendars = calendars.size();

            for (int i = 0; i < numCalendars; i++)
            {
                if (calendars.get(i).title.compareTo("Driving") == 0)
                {
                    driveCalendar = calendars.get(i);
                    getAddress();
                    break;
                }
            }
        } catch (IOException e)
        {
            handleException(e);
            Log.e("CJD", "Unable to get calendars " + e.toString());
            progressHandler.sendEmptyMessage(0);
            Toast toast = Toast.makeText(getApplicationContext(),
                    "Unable to get Driving calendar", 1000);
            toast.show();
        }
    }

    void handleException(Exception e)
    {
        e.printStackTrace();
        if (e instanceof HttpResponseException)
        {
            HttpResponse response = ((HttpResponseException) e).getResponse();
            int statusCode = response.getStatusCode();
            try
            {
                response.ignore();
            } catch (IOException e1)
            {
                e1.printStackTrace();
            }
            // TODO(yanivi): should only try this once to avoid infinite loop
            if (statusCode == 401)
            {
                getCalendarAccount();
                return;
            }
            try
            {
                Log.e(TAG, response.parseAsString());
            } catch (IOException parseException)
            {
                parseException.printStackTrace();
            }
        }
        Log.e(TAG, e.getMessage(), e);
    }

    private static CalendarUrl forRoot()
    {
        return new CalendarUrl(CalendarUrl.ROOT_URL);
    }

    private static CalendarUrl forCalendarMetafeed()
    {
        CalendarUrl result = forRoot();
        result.getPathParts().add("default");
        return result;
    }

    private static CalendarUrl forAllCalendarsFeed()
    {
        CalendarUrl result = forCalendarMetafeed();
        result.getPathParts().add("allcalendars");
        result.getPathParts().add("full");
        return result;
    }

}
