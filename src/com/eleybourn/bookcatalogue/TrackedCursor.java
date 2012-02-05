package com.eleybourn.bookcatalogue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;

import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteCursorDriver;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteQuery;

/**
 * DEBUG CLASS to help debug cursor leakage.
 * 
 * TODO: Turn this into 'SynchronizedCursor' and use non-static factory (in sync object?)
 * to allow sync object to be passed on creation. Then...override move*() to allow locking.
 * 
 * By using TrackedCursorFactory it is possible to use this class to analyze when and
 * where cursors are being allocated, and whether they are being deallocated in a timely
 * fashion.
 * 
 * @author Grunthos
 */
public class TrackedCursor extends SQLiteCursor  {

	/* Static Data */
	/* =========== */

	/** Static Factory object to create the custom cursor */
	public static final CursorFactory TrackedCursorFactory = new CursorFactory() {
			@Override
			public Cursor newCursor(
					SQLiteDatabase db,
					SQLiteCursorDriver masterQuery, 
					String editTable,
					SQLiteQuery query) 
			{
				return new TrackedCursor(db, masterQuery, editTable, query);
			}
	};

	/** Used as a collection of known cursors */
	private static HashSet<WeakReference<TrackedCursor>> mCursors = new HashSet<WeakReference<TrackedCursor>>();
	/** Global counter for unique cursor IDs */
	private static Long mIdCounter = 0L;

	/* Instance Data */
	/* ============= */

	/** ID of the current cursor */
	private final Long mId;
	/** We record a stack track when a cursor is created. */
	private StackTraceElement[] mStackTrace;
	/** Weak reference to this object, used in cursor collection */
	private WeakReference<TrackedCursor> mWeakRef;

	/**
	 * Constructor.
	 *
	 * @param db
	 * @param driver
	 * @param editTable
	 * @param query
	 */
	public TrackedCursor(SQLiteDatabase db, SQLiteCursorDriver driver, String editTable, SQLiteQuery query) {
		super(db, driver, editTable, query);
		
		// Record who called us. It's only from about the 7th element that matters.
		mStackTrace = Thread.currentThread().getStackTrace();

		// Get the next ID
		synchronized(mIdCounter)
		{
			mId = ++mIdCounter;
		}
		// Save this cursor in the collection
		synchronized(mCursors) {
			mWeakRef = new WeakReference<TrackedCursor>(this);
			mCursors.add(mWeakRef);
		}
	}

	/**
	 * Remove from collection on close.
	 */
	@Override
	public void close() {
		super.close();
		if (mWeakRef != null)
			synchronized(mCursors) {
				mCursors.remove(mWeakRef);
				mWeakRef.clear();
				mWeakRef = null;
			}
	}

	/**
	 * Finalizer that does sanity check. Setting a break here can catch the exact moment that
	 * a cursor is deleted before being closed.
	 */
	@Override
	public void finalize() {
		if (mWeakRef != null) {
			// This is a cursor that is being deleted before it is closed.
			// Setting a break here is sometimes useful.
			synchronized(mCursors) {
				mCursors.remove(mWeakRef);
				mWeakRef.clear();
				mWeakRef = null;
			}
		}
		super.finalize();
	}
	/**
	 * Get the stack trace recorded when cursor created
	 * @return
	 */
	public StackTraceElement[] getStackTrace() {
		return mStackTrace;
	}
	/**
	 * Get the ID of this cursor
	 * @return
	 */
	final public long getCursorId() {
		return mId;
	}

	/**
	 * Get the total number of cursors that have not called close(). This is subtly
	 * different from the list of open cursors because non-referenced cursors may 
	 * have been deleted and the finalizer not called.
	 * 
	 * @return
	 */
	public static long getCursorCountApproximate() {
		long count = 0;

		synchronized(mCursors) {
			count = mCursors.size();
		}
		return count;
	}

	/**
	 * Get the total number of open cursors; verifies that existing weak refs are valid
	 * and removes from collection if not. 
	 * 
	 * Note: This is not a *cheap* operation.
	 * 
	 * @return
	 */
	public static long getCursorCount() {
		long count = 0;

		ArrayList<WeakReference<TrackedCursor>> list = new ArrayList<WeakReference<TrackedCursor>>();
		synchronized(mCursors) {
			for(WeakReference<TrackedCursor> r : mCursors) {
				TrackedCursor c = r.get();
				if (c != null)
					count++;
				else
					list.add(r);
			}
			for(WeakReference<TrackedCursor> r : list) {
				mCursors.remove(r);
			}
		}
		return count;
	}

	/**
	 * Dump all open cursors to System.out.
	 */
	public static void dumpCursors() {
		for(TrackedCursor c : getCursors()) {
			System.out.println("Cursor " + c.getCursorId());
			for (StackTraceElement s : c.getStackTrace()) {
				System.out.println(s.getFileName() + "    Line " + s.getLineNumber() + " Method " + s.getMethodName());
			}
		}
	}

	/**
	 * Get a collection of open cursors at the current time.
	 *
	 * @return
	 */
	public static ArrayList<TrackedCursor> getCursors() {
		ArrayList<TrackedCursor> list = new ArrayList<TrackedCursor>();
		synchronized(mCursors) {
			for(WeakReference<TrackedCursor> r : mCursors) {
				TrackedCursor c = r.get();
				if (c != null)
					list.add(c);
			}
		}
		return list;		
	}
}