/* Scaler -- Scaler servlet main class

  Digital Image Library servlet components

  Copyright (C) 2001, 2002, 2003 Robert Casties (robcast@mail.berlios.de)

  This program is free software; you can redistribute  it and/or modify it
  under  the terms of  the GNU General  Public License as published by the
  Free Software Foundation;  either version 2 of the  License, or (at your
  option) any later version.
   
  Please read license.txt for the full details. A copy of the GPL
  may be found at http://www.gnu.org/copyleft/lgpl.html

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/

package digilib.servlet;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import digilib.Utils;
import digilib.auth.AuthOpException;
import digilib.auth.AuthOps;
import digilib.image.DocuImage;
import digilib.image.ImageOpException;
import digilib.io.FileOpException;
import digilib.io.FileOps;

//import tilecachetool.*;

/**
 * @author casties
 *
 */
//public class Scaler extends HttpServlet implements SingleThreadModel {
public class Scaler extends HttpServlet {

	// digilib servlet version (for all components)
	public static final String dlVersion = "1.6b";

	// Utils instance with debuglevel
	Utils util;
	// FileOps instance
	FileOps fileOp;
	// AuthOps instance
	AuthOps authOp;
	// ServletOps instance
	ServletOps servletOp;

	// DigilibParameters instance
	DigilibConfiguration dlConfig;

	// use authorization database
	boolean useAuthentication = true;

	/** Initialisation on first run.
	 * 
	 * @see javax.servlet.Servlet#init(javax.servlet.ServletConfig)
	 */
	public void init(ServletConfig config) throws ServletException {
		super.init(config);

		// Debuggin!
		//TCTool tctool = new TCTool();

		// get our ServletContext
		ServletContext context = config.getServletContext();
		// see if there is a Configuration instance
		dlConfig =
			(DigilibConfiguration) context.getAttribute(
				"digilib.servlet.parameters");
		if (dlConfig == null) {
			// create new Configuration
			try {
				dlConfig = new DigilibConfiguration(config);
			} catch (Exception e) {
				throw new ServletException(e);
			}
		}
		// set the servlet version
		dlConfig.setServletVersion(dlVersion);
		// first we need an Utils
		util = dlConfig.getUtil();
		// set our AuthOps
		useAuthentication = dlConfig.isUseAuthentication();
		authOp = dlConfig.getAuthOp();
		// FileOps instance
		fileOp = new FileOps(util);
		// AuthOps instance
		servletOp = new ServletOps(util);
	}

	/** Process the HTTP Get request*/
	public void doGet(HttpServletRequest request, HttpServletResponse response)
		throws ServletException, IOException {
		util.dprintln(1, "The servlet has received a GET!");
		// create new request with defaults
		DigilibRequest dlReq = new DigilibRequest();
		// set with request parameters
		dlReq.setWithRequest(request);
		// add DigilibRequest to ServletRequest
		request.setAttribute("digilib.servlet.request", dlReq);
		// do the processing
		processRequest(request, response);
	}

	/**Process the HTTP Post request*/
	public void doPost(
		HttpServletRequest request,
		HttpServletResponse response)
		throws ServletException, IOException {
		util.dprintln(1, "The servlet has received a POST!");
		// create new request with defaults
		DigilibRequest dlReq = new DigilibRequest();
		// set with request parameters
		dlReq.setWithRequest(request);
		// add DigilibRequest to ServletRequest
		request.setAttribute("digilib.servlet.request", dlReq);
		// do the processing
		processRequest(request, response);
	}

	/** main request handler. */
	void processRequest(
		HttpServletRequest request,
		HttpServletResponse response)
		throws ServletException, IOException {

		// time for benchmarking
		long startTime = System.currentTimeMillis();
		// output mime/type
		String mimeType = "image/png";

		/*
		 * parameters for a session
		 */

		// scale the image file to fit window size i.e. respect dw,dh
		boolean scaleToFit = true;
		// crop the image if needed
		boolean cropToFit = true;
		// use heuristics (GIF?) to scale or send as is
		boolean autoScale = true;
		// try prescaled images first
		boolean preScaledFirst = true;
		// interpolation to use for scaling
		int scaleQual = 0;
		// send html error message (or image file)
		boolean errorMsgHtml = false;
		// mirror the image
		boolean doMirror = false;
		// angle of mirror axis
		double mirrorAngle = 0;

		/*
		 *  request parameters
		 */

		DigilibRequest dlRequest =
			(DigilibRequest) request.getAttribute("digilib.servlet.request");

		// destination image width
		int paramDW = dlRequest.getDw();
		// destination image height
		int paramDH = dlRequest.getDh();
		// relative area x_offset (0..1)
		double paramWX = dlRequest.getWx();
		// relative area y_offset
		double paramWY = dlRequest.getWy();
		// relative area width (0..1)
		double paramWW = dlRequest.getWw();
		// relative area height
		double paramWH = dlRequest.getWh();
		// scale factor (additional to dw/width, dh/height)
		double paramWS = dlRequest.getWs();
		// rotation angle
		double paramROT = dlRequest.getRot();
		// contrast enhancement
		double paramCONT = dlRequest.getCont();
		// brightness enhancement
		double paramBRGT = dlRequest.getBrgt();

		/* operation mode: "fit": always fit to page, 
		 * "clip": send original resolution cropped, "file": send whole file (if
		 * allowed)
		 */
		if (dlRequest.isOption("clip")) {
			scaleToFit = false;
			cropToFit = true;
			autoScale = false;
		} else if (dlRequest.isOption("fit")) {
			scaleToFit = true;
			cropToFit = true;
			autoScale = false;
		} else if (dlRequest.isOption("file")) {
			scaleToFit = false;
			if (dlConfig.isSendFileAllowed()) {
				cropToFit = false;
			} else {
				cropToFit = true;
			}
			autoScale = false;
		}
		// operation mode: "errtxt": error message in html, "errimg": error image
		if (dlRequest.isOption("errtxt")) {
			errorMsgHtml = true;
		} else if (dlRequest.isOption("errimg")) {
			errorMsgHtml = false;
		}
		// operation mode: "q0" - "q2": interpolation quality
		if (dlRequest.isOption("q0")) {
			scaleQual = 0;
		} else if (dlRequest.isOption("q1")) {
			scaleQual = 1;
		} else if (dlRequest.isOption("q2")) {
			scaleQual = 2;
		}
		// operation mode: "lores": try to use scaled image, "hires": use unscaled image
		if (dlRequest.isOption("lores")) {
			preScaledFirst = true;
		} else if (dlRequest.isOption("hires")) {
			preScaledFirst = false;
		}
		// operation mode: "hmir": mirror horizontally, "vmir": mirror vertically
		if (dlRequest.isOption("hmir")) {
			doMirror = true;
			mirrorAngle = 0;
		} else if (dlRequest.isOption("vmir")) {
			doMirror = true;
			mirrorAngle = 90;
		}

		//"big" try for all file/image actions
		try {

			// DocuImage instance
			DocuImage docuImage = dlConfig.getDocuImageInstance();
			if (docuImage == null) {
				throw new ImageOpException("Unable to load DocuImage class!");
			}

			// set interpolation quality
			docuImage.setQuality(scaleQual);

			/*
			 *  find the file to load/send
			 */

			// get PathInfo
			String loadPathName = dlRequest.getFilePath();
			// if it's zoomed, try hires version (to be optimized...)
			if ((paramWW < 1f) || (paramWH < 1f)) {
				preScaledFirst = false;
			}

			/*
			 * check permissions
			 */
			if (useAuthentication) {
				// get a list of required roles (empty if no restrictions)
				List rolesRequired = authOp.rolesForPath(loadPathName, request);
				if (rolesRequired != null) {
					util.dprintln(1, "Role required: " + rolesRequired);
					util.dprintln(2, "User: " + request.getRemoteUser());
					// is the current request/user authorized?
					if (!authOp.isRoleAuthorized(rolesRequired, request)) {
						// send deny answer and abort
						util.dprintln(1, "ERROR: access denied!");
						if (errorMsgHtml) {
							ServletOps.htmlMessage(
								"ERROR: Unauthorized access!",
								response);
						} else {
							servletOp.sendFile(
								new File(dlConfig.getDenyImgFileName()),
								response);
						}
						return;
					}
				}
			}

			// find the file
			File fileToLoad =
				fileOp.getFileVariant(
					dlConfig.getBaseDirs(),
					loadPathName,
					dlRequest.getPn(),
					preScaledFirst);

			util.dprintln(1, "Loading: " + fileToLoad);

			// get the source image type (if it's known)
			mimeType = FileOps.mimeForFile(fileToLoad);

			/* if autoScale and not zoomed and source is GIF/PNG 
			 * then send as is.
			 */
			if ((autoScale
				&& (mimeType == "image/gif" || mimeType == "image/png")
				&& (paramWW == 1f)
				&& (paramWH == 1f))
				|| (autoScale && !(scaleToFit || cropToFit))) {

				util.dprintln(1, "Sending File as is.");

				servletOp.sendFile(fileToLoad, response);

				util.dprintln(
					1,
					"Done in "
						+ (System.currentTimeMillis() - startTime)
						+ "ms");
				return;
			}

			// finally load the file
			docuImage.loadImage(fileToLoad);

			/*
			 *  crop and scale the image
			 */

			// get image size
			int imgWidth = docuImage.getWidth();
			int imgHeight = docuImage.getHeight();

			util.dprintln(2, "IMG: " + imgWidth + "x" + imgHeight);
			util.dprintln(
				2,
				"time " + (System.currentTimeMillis() - startTime) + "ms");

			// coordinates using Java2D
			// image size
			Rectangle2D imgBounds = new Rectangle2D.Double(0, 0, imgWidth, imgHeight);
			// user window area in 4-point form (ul, ur, ll, lr)
			Point2D[] userAreaC = {
				new Point2D.Double(paramWX, paramWY),
				new Point2D.Double(paramWX + paramWW, paramWY),
				new Point2D.Double(paramWX, paramWY + paramWH),
				new Point2D.Double(paramWX + paramWW, paramWY + paramWH)
			};
			// transformation from relative [0,1] to image coordinates.
			AffineTransform imgTrafo = new AffineTransform();
			imgTrafo.scale(imgWidth, imgHeight);
			// rotate coordinates
			//imgTrafo.rotate(Math.toRadians(-paramROT));
			
			// coordinates and scaling
			double areaXoff;
			double areaYoff;
			double areaWidth;
			double areaHeight;
			double scaleX;
			double scaleY;
			double scaleXY;

			if (scaleToFit) {
				// calculate absolute from relative coordinates
				areaXoff = paramWX * imgWidth;
				areaYoff = paramWY * imgHeight;
				areaWidth = paramWW * imgWidth;
				areaHeight = paramWH * imgHeight;
				// calculate scaling factors
				scaleX = paramDW / areaWidth * paramWS;
				scaleY = paramDH / areaHeight * paramWS;
				scaleXY = (scaleX > scaleY) ? scaleY : scaleX;
			} else {
				// crop to fit
				// calculate absolute from relative coordinates
				areaXoff = paramWX * imgWidth;
				areaYoff = paramWY * imgHeight;
				areaWidth = paramDW;
				areaHeight = paramDH;
				// calculate scaling factors
				scaleX = 1f;
				scaleY = 1f;
				scaleXY = 1f;
			}

			util.dprintln(
				1,
				"Scale "
					+ scaleXY
					+ "("
					+ scaleX
					+ ","
					+ scaleY
					+ ") on "
					+ areaXoff
					+ ","
					+ areaYoff
					+ " "
					+ areaWidth
					+ "x"
					+ areaHeight);

			// Java2D 
			Point2D[] imgAreaC = { null, null, null, null };

			imgTrafo.transform(userAreaC, 0, imgAreaC, 0, 4);
			areaXoff = imgAreaC[0].getX();
			areaYoff = imgAreaC[0].getY();
			areaWidth = imgAreaC[0].distance(imgAreaC[1]);
			areaHeight = imgAreaC[0].distance(imgAreaC[2]);
			Rectangle2D imgArea =
				new Rectangle2D.Double(
					areaXoff,
					areaYoff,
					areaWidth,
					areaHeight);
			// calculate scaling factors
			scaleX = paramDW / areaWidth * paramWS;
			scaleY = paramDH / areaHeight * paramWS;
			scaleXY = (scaleX > scaleY) ? scaleY : scaleX;

			util.dprintln(
				1,
				"Scale "
					+ scaleXY
					+ "("
					+ scaleX
					+ ","
					+ scaleY
					+ ") on "
					+ areaXoff
					+ ","
					+ areaYoff
					+ " "
					+ areaWidth
					+ "x"
					+ areaHeight);

			// clip area at the image border
			/* areaWidth =
				(areaXoff + areaWidth > imgWidth)
					? imgWidth - areaXoff
					: areaWidth;
			areaHeight =
				(areaYoff + areaHeight > imgHeight)
					? imgHeight - areaYoff
					: areaHeight;
			*/
			imgArea = imgArea.createIntersection(imgBounds);
			areaWidth = imgArea.getWidth();
			areaHeight = imgArea.getHeight();

			util.dprintln(
				2,
				"cropped: "
					+ areaXoff
					+ ","
					+ areaYoff
					+ " "
					+ areaWidth
					+ "x"
					+ areaHeight);

			// check image parameters sanity
			if ((areaWidth < 1)
				|| (areaHeight < 1)
				|| (scaleXY * areaWidth < 2)
				|| (scaleXY * areaHeight < 2)) {
				util.dprintln(1, "ERROR: invalid scale parameter set!");
				throw new ImageOpException("Invalid scale parameter set!");
			}

			// crop and scale image
			docuImage.crop(
				(int) areaXoff,
				(int) areaYoff,
				(int) areaWidth,
				(int) areaHeight);

			docuImage.scale(scaleXY);

			// mirror image
			if (doMirror) {
				docuImage.mirror(mirrorAngle);
			}

			// rotate image (first shot :-)
			if (paramROT != 0) {
				docuImage.rotate(paramROT);
			}

			// contrast and brightness enhancement
			if ((paramCONT != 0) || (paramBRGT != 0)) {
				double mult = Math.pow(2, paramCONT); 
				docuImage.enhance(mult, paramBRGT);
			}

			util.dprintln(
				2,
				"time " + (System.currentTimeMillis() - startTime) + "ms");

			/*
			 *  write the resulting image
			 */

			// setup output -- if source is JPG then dest will be JPG else it's PNG
			if (mimeType != "image/jpeg") {
				mimeType = "image/png";
			}
			response.setContentType(mimeType);

			// write the image
			docuImage.writeImage(mimeType, response.getOutputStream());

			util.dprintln(
				1,
				"Done in " + (System.currentTimeMillis() - startTime) + "ms");

			/*
			 *  error handling
			 */

		} // end of "big" try
		catch (FileOpException e) {
			util.dprintln(1, "ERROR: File IO Error: " + e);
			try {
				if (errorMsgHtml) {
					ServletOps.htmlMessage(
						"ERROR: File IO Error: " + e,
						response);
				} else {
					servletOp.sendFile(
						new File(dlConfig.getErrorImgFileName()),
						response);
				}
			} catch (FileOpException ex) {
			} // so we don't get a loop
			return;
		} catch (AuthOpException e) {
			util.dprintln(1, "ERROR: Authorization error: " + e);
			try {
				if (errorMsgHtml) {
					ServletOps.htmlMessage(
						"ERROR: Authorization error: " + e,
						response);
				} else {
					servletOp.sendFile(
						new File(dlConfig.getErrorImgFileName()),
						response);
				}
			} catch (FileOpException ex) {
			} // so we don't get a loop
			return;
		} catch (ImageOpException e) {
			util.dprintln(1, "ERROR: Image Error: " + e);
			try {
				if (errorMsgHtml) {
					ServletOps.htmlMessage(
						"ERROR: Image Operation Error: " + e,
						response);
				} else {
					servletOp.sendFile(
						new File(dlConfig.getErrorImgFileName()),
						response);
				}
			} catch (FileOpException ex) {
			} // so we don't get a loop
			return;
		} catch (RuntimeException e) {
			// JAI likes to throw RuntimeExceptions ;-(
			util.dprintln(1, "ERROR: Other Image Error: " + e);
			try {
				if (errorMsgHtml) {
					ServletOps.htmlMessage(
						"ERROR: Other Image Operation Error: " + e,
						response);
				} else {
					servletOp.sendFile(
						new File(dlConfig.getErrorImgFileName()),
						response);
				}
			} catch (FileOpException ex) {
			} // so we don't get a loop
			return;
		}
	}

} //Scaler class
