package org.docear.pdf;


import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.RenderedImage;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;

import de.intarsys.pdf.content.CSContent;
import de.intarsys.pdf.cos.COSDictionary;
import de.intarsys.pdf.cos.COSName;
import de.intarsys.pdf.pd.*;
import org.docear.pdf.feature.ADocumentCreator;
import org.docear.pdf.image.CSImageExtractor;
import org.docear.pdf.image.IDocearPdfImageHandler;
import org.docear.pdf.image.UniqueImageHashExtractor;
import org.docear.pdf.ocr.OCRTextExtractor;
import org.docear.pdf.text.CSFormatedTextExtractor;
import org.docear.pdf.text.PdfTextEntity;
import org.docear.pdf.util.CharSequenceFilter;
import org.docear.pdf.util.ReplaceLigaturesFilter;

import de.intarsys.pdf.content.CSDeviceBasedInterpreter;
import de.intarsys.pdf.content.CSException;
import de.intarsys.pdf.content.text.CSTextExtractor;
import de.intarsys.pdf.cos.COSDocument;
import de.intarsys.pdf.cos.COSInfoDict;
import de.intarsys.pdf.tools.kernel.PDFGeometryTools;
import org.ghost4j.document.PDFDocument;
import org.ghost4j.renderer.SimpleRenderer;
import org.xeustechnologies.googleapi.spelling.SpellChecker;
import org.xeustechnologies.googleapi.spelling.SpellCorrection;
import org.xeustechnologies.googleapi.spelling.SpellResponse;

import javax.imageio.ImageIO;

public class PdfDataExtractor {
	private CharSequenceFilter filter = new ReplaceLigaturesFilter();
	private final File file; 
	private String uniqueHash = null;
	private PDDocument document;
	private COSDocument cosDoc;
	
	public PdfDataExtractor(URI filePath) {
		this(new File(filePath));		
	}
	
	public PdfDataExtractor(File file) {
		if(file == null) {
			throw new IllegalArgumentException("NULL");
		}
		this.file = file;
	}
	
	public String extractPlainText() throws IOException {
		StringBuilder sb = new StringBuilder();
		try {
			extractText(getPDDocument(getDocument()).getPageTree(), sb);
		} finally {
			close();
		}
		return sb.toString();
	}
	
	private void extractText(PDPageTree pageTree, StringBuilder sb) {
		for (Iterator<?> it = pageTree.getKids().iterator(); it.hasNext();) {
			PDPageNode node = (PDPageNode) it.next();
			if (node.isPage()) {
				try {
					CSTextExtractor extractor = new CSTextExtractor();
					PDPage page = (PDPage) node;
					AffineTransform pageTx = new AffineTransform();
					PDFGeometryTools.adjustTransform(pageTx, page);
					extractor.setDeviceTransform(pageTx);
					CSDeviceBasedInterpreter interpreter = new CSDeviceBasedInterpreter(null, extractor);
					interpreter.process(page.getContentStream(), page.getResources());
					sb.append(extractor.getContent());
				} catch (CSException e) {
					e.printStackTrace();
				}
			} else {
				extractText((PDPageTree) node, sb);
			}
		}
	}
		
	public String extractTitle() throws IOException {
		int TITLE_MIN_LENGTH = 2;
		String title = null;
		try {
			PDPage page = getPDDocument(getDocument()).getPageTree().getFirstPage();
			if (page.isPage()) {
				try {
					if(!page.cosGetContents().basicIterator().hasNext()) {
						page = page.getNextPage();
					}
									
					TreeMap<PdfTextEntity, StringBuilder> map = tryTextExtraction(page);
					Entry<PdfTextEntity, StringBuilder> entry = map.firstEntry();
					if(entry == null) {
						// Lo intento por mi cuenta

						char[] chars = "abcdefghijklmnopqrstuvwxyz".toCharArray();
						StringBuilder bu = new StringBuilder();
						Random random = new Random();
						for (int i = 0; i < 20; i++) {
							char c = chars[random.nextInt(chars.length)];
							bu.append(c);
						}
						String auxFileName = bu.toString();

						PDFDocument document = new PDFDocument();
						document.load(file);

						SimpleRenderer renderer = new SimpleRenderer();

						// set resolution (in DPI)
						renderer.setResolution(200);

						List<Image> images = renderer.render(document, 0, 0);
						for (int i = 0; i < images.size(); i++) {
							ImageIO.write((RenderedImage) images.get(i), "jpg", new File("/tmp/"+auxFileName+".jpg"));
						}

						Runtime.getRuntime().exec(new String[]{"tesseract", "/tmp/"+auxFileName+".jpg", "/tmp/"+auxFileName+".jpg", "-l spa", "text"}).waitFor();

						BufferedReader br = new BufferedReader(new FileReader("/tmp/"+auxFileName+".jpg.txt"));
						try {
							StringBuilder sb = new StringBuilder();
							String line = br.readLine();

							while (line != null) {
								sb.append(line);
								sb.append("\n");
								line = br.readLine();
							}
							title = sb.toString().replace("\n", " ");
						} finally {
							br.close();
						}
//						OCRTextExtractor handler = new OCRTextExtractor(new File("/tmp/1.jpg"));
//						tryImageExtraction(page, handler);
//						map = handler.getMap();
//						entry = map.firstEntry();

						File auxImage = new File("/tmp/"+auxFileName+".jpg");
						File auxText = new File("/tmp/"+auxFileName+".jpg.txt");
						auxImage.delete();
						auxText.delete();

						if(title.compareTo("")==0) {
							COSInfoDict info = getDocument().getInfoDict();
							title = info.getTitle();
						}
					}
					else {
						title = entry.getValue().toString().trim();
						while(title.trim().length() < TITLE_MIN_LENGTH || isNumber(title)) {
							entry = map.higherEntry(entry.getKey());
							if(entry == null) {
								break;
							}
							title = entry.getValue().toString().trim();
						}
						if(title.trim().length() < TITLE_MIN_LENGTH || isNumber(title)) {
							title = null;
						}
					}
					//System.out.println(map);
				}
				catch (Exception ex) {
					COSInfoDict info = getDocument().getInfoDict();
					if (info != null) {
						title = info.getTitle();
					}
				}
			}
		}
		finally {
			close();
		}
		if(title != null) {
			try {
				title = filter.filter(title);
			} catch (IOException e) {
			}
		}
		return title;
	}

	private void onlyHashExtraction() throws IOException {
		try {
			PDPage page = getPDDocument(getDocument()).getPageTree().getFirstPage();
			if (page.isPage()) {
				try {
					if(!page.cosGetContents().basicIterator().hasNext()) {
						page = page.getNextPage();
					}
					TreeMap<PdfTextEntity, StringBuilder> map = tryTextExtraction(page);
					Entry<PdfTextEntity, StringBuilder> entry = map.firstEntry();
					if(entry == null) {
						UniqueImageHashExtractor handler = new UniqueImageHashExtractor();
						tryImageExtraction(page, handler);
						uniqueHash = handler.getUniqueHash();
					}
				}
				catch (Exception ex) {
				}
			}
		}
		finally {
			close();
		}
	}

	private TreeMap<PdfTextEntity, StringBuilder> tryTextExtraction(PDPage page) {
		CSFormatedTextExtractor extractor = new CSFormatedTextExtractor();
								
		AffineTransform pageTx = new AffineTransform();
		PDFGeometryTools.adjustTransform(pageTx, page);
		extractor.setDeviceTransform(pageTx);
		CSDeviceBasedInterpreter interpreter = new CSDeviceBasedInterpreter(null, extractor);
		interpreter.process(page.getContentStream(), page.getResources());
		TreeMap<PdfTextEntity, StringBuilder> map = extractor.getMap();
		uniqueHash = extractor.getHash();
		return map;
	}
	
	private void tryImageExtraction(PDPage page, IDocearPdfImageHandler imageHandler) {
		CSImageExtractor ocrExtractor = new CSImageExtractor(imageHandler);
		CSDeviceBasedInterpreter interpreter = new CSDeviceBasedInterpreter(null, ocrExtractor);
		interpreter.process(page.getContentStream(), page.getResources());
	}
		
	public String getUniqueHashCode() throws IOException {
		if(uniqueHash == null) {
			onlyHashExtraction();
		}
		if(this.uniqueHash == null) {
			return null;
		}
		return this.uniqueHash.toUpperCase();
	}
	
	private boolean isNumber(String title) {
		try {
			Double.parseDouble(title.trim());
		}
		catch (Exception e) {
			return false;
		}
		return true;
	}

	public COSDocument getDocument() throws IOException {
		synchronized (this) {
			if(cosDoc == null) {
				cosDoc = ADocumentCreator.getDocument(file);
			}
			return cosDoc;
		}
	}
	
	public PDDocument getPDDocument(COSDocument cosDoc) throws IOException {
		synchronized (this) {
			try {
				if(document == null) {
					document = ADocumentCreator.getPDDocument(cosDoc);
				}
				return document;
			}
			catch (Exception e) {
				throw new IOException(e);
			}
		}
	}
	protected final void finalize() { 
		close();
	}
	
	public boolean close() {
		synchronized (this) {
			if(cosDoc != null) {
				try {
					cosDoc.close();
					cosDoc = null;
				} catch (IOException e) {
					e.printStackTrace();
					return false;
				}
				
			}
			if(document != null) {
				try {
					document.close();
					document = null;
				} catch (IOException e) {
					e.printStackTrace();
					return false;
				}
				
			}
			return true;
		}
	}
}
