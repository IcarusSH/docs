package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.sismics.docs.core.constant.PermType;
import com.sismics.docs.core.dao.AclDao;
import com.sismics.docs.core.dao.DocumentDao;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.dao.dto.DocumentDto;
import com.sismics.docs.core.event.DocumentUpdatedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.event.FileUpdatedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.docs.core.util.AuditLogUtil;
import com.sismics.docs.core.util.DirectoryUtil;
import com.sismics.docs.core.util.EncryptionUtil;
import com.sismics.docs.core.util.FileUtil;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.RestUtil;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.util.HttpUtil;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.mime.MimeType;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * File REST resources.
 * 
 * @author bgamard
 */
@Path("/file")
public class FileResource extends BaseResource {
    /**
     * Add a file (with or without a document).
     *
     * @api {put} /file Add a file
     * @apiDescription A file can be added without associated document, and will go in a temporary storage waiting for one.
     * This resource accepts only multipart/form-data.
     * @apiName PutFile
     * @apiGroup File
     * @apiParam {String} [id] Document ID
     * @apiParam {String} [previousFileId] ID of the file to replace by this new version
     * @apiParam {String} file File data
     * @apiSuccess {String} status Status OK
     * @apiSuccess {String} id File ID
     * @apiSuccess {Number} size File size (in bytes)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) NotFound Document not found
     * @apiError (server) StreamError Error reading the input file
     * @apiError (server) ErrorGuessMime Error guessing mime type
     * @apiError (client) QuotaReached Quota limit reached
     * @apiError (server) FileError Error adding a file
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param fileBodyPart File to add
     * @return Response
     */
    @PUT
    @Consumes("multipart/form-data")
    public Response add(
            @FormDataParam("id") String documentId,
            @FormDataParam("previousFileId") String previousFileId,
            @FormDataParam("file") FormDataBodyPart fileBodyPart) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        ValidationUtil.validateRequired(fileBodyPart, "file");

        // Get the document
        DocumentDto documentDto = null;
        if (Strings.isNullOrEmpty(documentId)) {
            documentId = null;
        } else {
            DocumentDao documentDao = new DocumentDao();
            documentDto = documentDao.getDocument(documentId, PermType.WRITE, getTargetIdList(null));
            if (documentDto == null) {
                throw new NotFoundException();
            }
        }
        
        // Keep unencrypted data temporary on disk
        String name = fileBodyPart.getContentDisposition() != null ?
                URLDecoder.decode(fileBodyPart.getContentDisposition().getFileName(), StandardCharsets.UTF_8) : null;
        java.nio.file.Path unencryptedFile;
        long fileSize;
        try {
            unencryptedFile = AppContext.getInstance().getFileService().createTemporaryFile(name);
            Files.copy(fileBodyPart.getValueAs(InputStream.class), unencryptedFile, StandardCopyOption.REPLACE_EXISTING);
            fileSize = Files.size(unencryptedFile);
        } catch (IOException e) {
            throw new ServerException("StreamError", "Error reading the input file", e);
        }

        try {
            String fileId = FileUtil.createFile(name, previousFileId, unencryptedFile, fileSize, documentDto == null ?
                    null : documentDto.getLanguage(), principal.getId(), documentId);

            // Always return OK
            JsonObjectBuilder response = Json.createObjectBuilder()
                    .add("status", "ok")
                    .add("id", fileId)
                    .add("size", fileSize);
            return Response.ok().entity(response.build()).build();
        } catch (IOException e) {
            throw new ClientException(e.getMessage(), e.getMessage(), e);
        } catch (Exception e) {
            throw new ServerException("FileError", "Error adding a file", e);
        }
    }
    
    /**
     * Attach a file to a document.
     *
     * @api {post} /file/:fileId/attach Attach a file to a document
     * @apiName PostFileAttach
     * @apiGroup File
     * @apiParam {String} fileId File ID
     * @apiParam {String} id Document ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) IllegalFile File not orphan
     * @apiError (server) AttachError Error attaching file to document
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id File ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/attach")
    public Response attach(
            @PathParam("id") String id,
            @FormParam("id") String documentId) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Validate input data
        ValidationUtil.validateRequired(documentId, "documentId");
        
        // Get the current user
        UserDao userDao = new UserDao();
        User user = userDao.getById(principal.getId());
        
        // Get the document and the file
        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();
        File file = fileDao.getFile(id, principal.getId());
        DocumentDto documentDto = documentDao.getDocument(documentId, PermType.WRITE, getTargetIdList(null));
        if (file == null || documentDto == null) {
            throw new NotFoundException();
        }
        
        // Check that the file is orphan
        if (file.getDocumentId() != null) {
            throw new ClientException("IllegalFile", MessageFormat.format("File not orphan: {0}", id));
        }
        
        // Update the file
        file.setDocumentId(documentId);
        file.setOrder(fileDao.getByDocumentId(principal.getId(), documentId).size());
        fileDao.update(file);
        
        // Raise a new file updated event and document updated event (it wasn't sent during file creation)
        try {
            java.nio.file.Path storedFile = DirectoryUtil.getStorageDirectory().resolve(id);
            java.nio.file.Path unencryptedFile = EncryptionUtil.decryptFile(storedFile, user.getPrivateKey());
            FileUtil.startProcessingFile(id);
            FileUpdatedAsyncEvent fileUpdatedAsyncEvent = new FileUpdatedAsyncEvent();
            fileUpdatedAsyncEvent.setUserId(principal.getId());
            fileUpdatedAsyncEvent.setLanguage(documentDto.getLanguage());
            fileUpdatedAsyncEvent.setFileId(file.getId());
            fileUpdatedAsyncEvent.setUnencryptedFile(unencryptedFile);
            ThreadLocalContext.get().addAsyncEvent(fileUpdatedAsyncEvent);
            
            DocumentUpdatedAsyncEvent documentUpdatedAsyncEvent = new DocumentUpdatedAsyncEvent();
            documentUpdatedAsyncEvent.setUserId(principal.getId());
            documentUpdatedAsyncEvent.setDocumentId(documentId);
            ThreadLocalContext.get().addAsyncEvent(documentUpdatedAsyncEvent);
        } catch (Exception e) {
            throw new ServerException("AttachError", "Error attaching file to document", e);
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Update a file.
     *
     * @api {post} /file/:id Update a file
     * @apiName PostFile
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiParam {String} name Name
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.6.0
     *
     * @param id File ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}")
    public Response update(@PathParam("id") String id,
                           @FormParam("name") String name) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the file
        File file = findFile(id, null, PermType.WRITE);

        // Validate input data
        name = ValidationUtil.validateLength(name, "name", 1, 200, false);

        // Update the file
        FileDao fileDao = new FileDao();
        file.setName(name);
        fileDao.update(file);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Process a file manually.
     *
     * @api {post} /file/:id/process Process a file manually
     * @apiName PostFileProcess
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (server) ProcessingError Processing error
     * @apiPermission user
     * @apiVersion 1.6.0
     *
     * @param id File ID
     * @return Response
     */
    @POST
    @Path("{id: [a-z0-9\\-]+}/process")
    public Response process(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the document and the file
        DocumentDao documentDao = new DocumentDao();
        FileDao fileDao = new FileDao();
        File file = fileDao.getFile(id);
        if (file == null || file.getDocumentId() == null) {
            throw new NotFoundException();
        }
        DocumentDto documentDto = documentDao.getDocument(file.getDocumentId(), PermType.WRITE, getTargetIdList(null));
        if (documentDto == null) {
            throw new NotFoundException();
        }

        // Get the creating user
        UserDao userDao = new UserDao();
        User user = userDao.getById(file.getUserId());

        // Start the processing asynchronously
        try {
            java.nio.file.Path storedFile = DirectoryUtil.getStorageDirectory().resolve(id);
            java.nio.file.Path unencryptedFile = EncryptionUtil.decryptFile(storedFile, user.getPrivateKey());
            FileUtil.startProcessingFile(id);
            FileUpdatedAsyncEvent event = new FileUpdatedAsyncEvent();
            event.setUserId(principal.getId());
            event.setLanguage(documentDto.getLanguage());
            event.setFileId(file.getId());
            event.setUnencryptedFile(unencryptedFile);
            ThreadLocalContext.get().addAsyncEvent(event);
        } catch (Exception e) {
            throw new ServerException("ProcessingError", "Error processing this file", e);
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Reorder files.
     *
     * @api {post} /file/:reorder Reorder files
     * @apiName PostFileReorder
     * @apiGroup File
     * @apiParam {String} id Document ID
     * @apiParam {String[]} order List of files ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) NotFound Document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param idList List of files ID in the new order
     * @return Response
     */
    @POST
    @Path("reorder")
    public Response reorder(
            @FormParam("id") String documentId,
            @FormParam("order") List<String> idList) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        
        // Validate input data
        ValidationUtil.validateRequired(documentId, "id");
        ValidationUtil.validateRequired(idList, "order");
        
        // Get the document
        AclDao aclDao = new AclDao();
        if (!aclDao.checkPermission(documentId, PermType.WRITE, getTargetIdList(null))) {
            throw new NotFoundException();
        }
        
        // Reorder files
        FileDao fileDao = new FileDao();
        for (File file : fileDao.getByDocumentId(principal.getId(), documentId)) {
            int order = idList.lastIndexOf(file.getId());
            if (order != -1) {
                file.setOrder(order);
            }
        }

        // Raise a document updated event
        DocumentUpdatedAsyncEvent event = new DocumentUpdatedAsyncEvent();
        event.setUserId(principal.getId());
        event.setDocumentId(documentId);
        ThreadLocalContext.get().addAsyncEvent(event);
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Returns files linked to a document or not linked to any document.
     *
     * @api {get} /file/list Get files
     * @apiName GetFileList
     * @apiGroup File
     * @apiParam {String} [id] Document ID
     * @apiParam {String} [share] Share ID
     * @apiSuccess {Object[]} files List of files
     * @apiSuccess {String} files.id ID
     * @apiSuccess {String} files.processing True if the file is currently processing
     * @apiSuccess {String} files.name File name
     * @apiSuccess {String} files.version Zero-based version number
     * @apiSuccess {String} files.mimetype MIME type
     * @apiSuccess {String} files.document_id Document ID
     * @apiSuccess {String} files.create_date Create date (timestamp)
     * @apiSuccess {String} files.size File size (in bytes)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound Document not found
     * @apiError (server) FileError Unable to get the size of a file
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param shareId Sharing ID
     * @return Response
     */
    @GET
    @Path("list")
    public Response list(
            @QueryParam("id") String documentId,
            @QueryParam("share") String shareId) {
        boolean authenticated = authenticate();
        
        // Check document visibility
        if (documentId != null) {
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(documentId, PermType.READ, getTargetIdList(shareId))) {
                throw new NotFoundException();
            }
        } else if (!authenticated) {
            throw new ForbiddenClientException();
        }

        FileDao fileDao = new FileDao();
        JsonArrayBuilder files = Json.createArrayBuilder();
        for (File fileDb : fileDao.getByDocumentId(principal.getId(), documentId)) {
            files.add(RestUtil.fileToJsonObjectBuilder(fileDb));
        }
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("files", files);

        return Response.ok().entity(response.build()).build();
    }

    /**
     * List all versions of a file.
     *
     * @api {get} /file/:id/versions Get versions of a file
     * @apiName GetFileVersions
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiSuccess {Object[]} files List of files
     * @apiSuccess {String} files.id ID
     * @apiSuccess {String} files.name File name
     * @apiSuccess {String} files.version Zero-based version number
     * @apiSuccess {String} files.mimetype MIME type
     * @apiSuccess {String} files.create_date Create date (timestamp)
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound File not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id File ID
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}/versions")
    public Response versions(@PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get versions
        File file = findFile(id, null, PermType.READ);
        FileDao fileDao = new FileDao();
        List<File> fileList = Lists.newArrayList(file);
        if (file.getVersionId() != null) {
            fileList = fileDao.getByVersionId(file.getVersionId());
        }

        JsonArrayBuilder files = Json.createArrayBuilder();
        for (File fileDb : fileList) {
            files.add(Json.createObjectBuilder()
                    .add("id", fileDb.getId())
                    .add("name", JsonUtil.nullable(fileDb.getName()))
                    .add("version", fileDb.getVersion())
                    .add("mimetype", fileDb.getMimeType())
                    .add("create_date", fileDb.getCreateDate().getTime()));
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("files", files);
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Deletes a file.
     *
     * @api {delete} /file/:id Delete a file
     * @apiName DeleteFile
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) NotFound File or document not found
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param id File ID
     * @return Response
     */
    @DELETE
    @Path("{id: [a-z0-9\\-]+}")
    public Response delete(
            @PathParam("id") String id) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the file
        File file = findFile(id, null, PermType.WRITE);

        // Delete the file
        FileDao fileDao = new FileDao();
        fileDao.delete(file.getId(), principal.getId());
        
        // Raise a new file deleted event
        FileDeletedAsyncEvent fileDeletedAsyncEvent = new FileDeletedAsyncEvent();
        fileDeletedAsyncEvent.setUserId(principal.getId());
        fileDeletedAsyncEvent.setFileId(file.getId());
        fileDeletedAsyncEvent.setFileSize(file.getSize());
        ThreadLocalContext.get().addAsyncEvent(fileDeletedAsyncEvent);
        
        if (file.getDocumentId() != null) {
            // Raise a new document updated
            DocumentUpdatedAsyncEvent documentUpdatedAsyncEvent = new DocumentUpdatedAsyncEvent();
            documentUpdatedAsyncEvent.setUserId(principal.getId());
            documentUpdatedAsyncEvent.setDocumentId(file.getDocumentId());
            ThreadLocalContext.get().addAsyncEvent(documentUpdatedAsyncEvent);
        }
        
        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }
    
    /**
     * Returns a file.
     *
     * @api {get} /file/:id/data Get a file data
     * @apiName GetFile
     * @apiGroup File
     * @apiParam {String} id File ID
     * @apiParam {String} share Share ID
     * @apiParam {String="web","thumb","content"} [size] Size variation
     * @apiSuccess {Object} file The file data is the whole response
     * @apiError (client) SizeError Size must be web or thumb
     * @apiError (client) ForbiddenError Access denied or document not visible
     * @apiError (client) NotFound File not found
     * @apiError (server) ServiceUnavailable Error reading the file
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param fileId File ID
     * @return Response
     */
    @GET
    @Path("{id: [a-z0-9\\-]+}/data")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_HTML, "text/plain"})
    public Response data(
            @PathParam("id") final String fileId,
            @QueryParam("share") String shareId,
            @QueryParam("size") String size,
            @HeaderParam(HttpHeaders.ACCEPT) String acceptHeader) {
        authenticate();

        if (size != null && !Lists.newArrayList("web", "thumb", "content").contains(size)) {
            throw new ClientException("SizeError", "Size must be web, thumb or content");
        }

        // Permission gate: original file (no size) and OCR text (size=content) require DOWNLOADABLE;
        // thumbnails / previews (size=web|thumb) only require READ so READ-only users can browse.
        // ?size=null   → DOWNLOADABLE (raw download)
        // ?size=content → DOWNLOADABLE (extracted OCR text — same sensitivity as the original)
        // ?size=web|thumb → READ (preview image, no sensitive payload)
        PermType requiredPerm = (size == null || size.equals("content")) ? PermType.DOWNLOADABLE : PermType.READ;

        // Get the file
        File file = findFile(fileId, shareId, requiredPerm);

        // Log the download event so it shows up in the file's parent document activity tab.
        // Thumbnail / preview requests ("size=thumb|web|content") are not logged to avoid noise.
        if (size == null && shareId == null) {
            AuditLogUtil.createDownloadLog(fileId, "File", principal.getId());
        }

        // Get the stored file
        UserDao userDao = new UserDao();
        java.nio.file.Path storedFile;
        String mimeType;
        boolean decrypt;
        if (size != null) {
            if (size.equals("content")) {
                String text = Strings.nullToEmpty(file.getContent());
                // 浏览器（Accept: text/html）请求时返回带水印的 HTML 包装页面，
                // 避免用户直接复制粘贴 URL 拿到裸 text/plain 后可随便外传。
                // API 客户端（curl / Restangular）默认 Accept 不含 text/html，
                // 仍会拿到裸 text/plain，行为不变。
                if (acceptHeader != null && acceptHeader.contains("text/html")) {
                    String docTitle = file.getDocumentId();
                    DocumentDao documentDao = new DocumentDao();
                    com.sismics.docs.core.model.jpa.Document doc = documentDao.getById(file.getDocumentId());
                    if (doc != null) {
                        docTitle = doc.getTitle();
                    }
                    String username = principal.getName();
                    long nowMs = System.currentTimeMillis();
                    String html = buildWatermarkedTextPage(text, username, docTitle, file.getName(), nowMs);
                    return Response.ok(html)
                            .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                            .build();
                }
                return Response.ok(text)
                        .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                        .build();
            }

            storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId + "_" + size);
            mimeType = MimeType.IMAGE_JPEG; // Thumbnails are JPEG
            decrypt = true; // Thumbnails are encrypted
            if (!Files.exists(storedFile)) {
                try {
                    storedFile = Paths.get(getClass().getResource("/image/file-" + size + ".png").toURI());
                } catch (URISyntaxException e) {
                    // Ignore
                }
                mimeType = MimeType.IMAGE_PNG;
                decrypt = false;
            }
        } else {
            storedFile = DirectoryUtil.getStorageDirectory().resolve(fileId);
            mimeType = file.getMimeType();
            decrypt = true; // Original files are encrypted
        }
        
        // Stream the output and decrypt it if necessary
        StreamingOutput stream;
        
        // A file is always encrypted by the creator of it
        User user = userDao.getById(file.getUserId());
        
        // Write the decrypted file to the output
        try {
            InputStream fileInputStream = Files.newInputStream(storedFile);
            final InputStream responseInputStream = decrypt ?
                    EncryptionUtil.decryptInputStream(fileInputStream, user.getPrivateKey()) : fileInputStream;
                    
            stream = outputStream -> {
                try {
                    ByteStreams.copy(responseInputStream, outputStream);
                } finally {
                    try {
                        responseInputStream.close();
                        outputStream.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            };
        } catch (Exception e) {
            return Response.status(Status.SERVICE_UNAVAILABLE).build();
        }

        Response.ResponseBuilder builder = Response.ok(stream)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFullName("data") + "\"")
                .header(HttpHeaders.CONTENT_TYPE, mimeType);
        if (decrypt) {
            // Cache real files
            builder.header(HttpHeaders.CACHE_CONTROL, "private")
                    .header(HttpHeaders.EXPIRES, HttpUtil.buildExpiresHeader(3_600_000L * 24L * 365L));
        } else {
            // Do not cache the temporary thumbnail
            builder.header(HttpHeaders.CACHE_CONTROL, "no-store, must-revalidate")
                    .header(HttpHeaders.EXPIRES, "0");
        }
        return builder.build();
    }

    /**
     * Returns all files from a document, zipped.
     *
     * @api {get} /file/zip Returns all files from a document, zipped.
     * @apiName GetFileZip
     * @apiGroup File
     * @apiParam {String} id Document ID
     * @apiParam {String} share Share ID
     * @apiSuccess {Object} file The ZIP file is the whole response
     * @apiError (client) NotFoundException Document not found
     * @apiError (server) InternalServerError Error creating the ZIP file
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param documentId Document ID
     * @param shareId Share ID
     * @return Response
     */
    @GET
    @Path("zip")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    public Response zip(
            @QueryParam("id") String documentId,
            @QueryParam("share") String shareId) {
        authenticate();

        // Get the document
        DocumentDao documentDao = new DocumentDao();
        // ZIP 是原始文件集合的打包下载，需要 DOWNLOADABLE 而不仅是 READ
        DocumentDto documentDto = documentDao.getDocument(documentId, PermType.DOWNLOADABLE, getTargetIdList(shareId));
        if (documentDto == null) {
            throw new NotFoundException();
        }

        // Get files associated with this document
        FileDao fileDao = new FileDao();
        final List<File> fileList = fileDao.getByDocumentId(principal.getId(), documentId);
        String zipFileName = documentDto.getTitle().replaceAll("\\W+", "_");
        if (shareId == null) {
            AuditLogUtil.createDownloadLog(documentId, "Document", principal.getId());
        }
        return sendZippedFiles(zipFileName, fileList);
    }

    /**
     * Returns a list of files, zipped
     *
     * @api {post} /file/zip Returns a list of files, zipped
     * @apiName GetFilesZip
     * @apiGroup File
     * @apiParam {String[]} files IDs
     * @apiSuccess {Object} file The ZIP file is the whole response
     * @apiError (client) NotFoundException Files not found
     * @apiError (server) InternalServerError Error creating the ZIP file
     * @apiPermission none
     * @apiVersion 1.11.0
     *
     * @param filesIdsList Files IDs
     * @return Response
     */
    @POST
    @Path("zip")
    @Produces({MediaType.APPLICATION_OCTET_STREAM, MediaType.TEXT_PLAIN})
    public Response zip(
            @FormParam("files") List<String> filesIdsList) {
        authenticate();
        // 批量下载原始文件，需要 DOWNLOADABLE
        List<File> fileList = findFiles(filesIdsList, PermType.DOWNLOADABLE);
        // 记录批量下载事件，否则审计线索缺失
        AuditLogUtil.createDownloadLog("bulk:" + filesIdsList, "File", principal.getId());
        return sendZippedFiles("files", fileList);
    }

    /**
     * Sent the content of a list of files.
     */
    private Response sendZippedFiles(String zipFileName, List<File> fileList) {
        final UserDao userDao = new UserDao();

        // Create the ZIP stream
        StreamingOutput stream = outputStream -> {
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {
                // Add each file to the ZIP stream
                int index = 0;
                for (File file : fileList) {
                    java.nio.file.Path storedfile = DirectoryUtil.getStorageDirectory().resolve(file.getId());
                    InputStream fileInputStream = Files.newInputStream(storedfile);

                    // Add the decrypted file to the ZIP stream
                    // Files are encrypted by the creator of them
                    User user = userDao.getById(file.getUserId());
                    try (InputStream decryptedStream = EncryptionUtil.decryptInputStream(fileInputStream, user.getPrivateKey())) {
                        ZipEntry zipEntry = new ZipEntry(index + "-" + file.getFullName(Integer.toString(index)));
                        zipOutputStream.putNextEntry(zipEntry);
                        ByteStreams.copy(decryptedStream, zipOutputStream);
                        zipOutputStream.closeEntry();
                    } catch (Exception e) {
                        throw new WebApplicationException(e);
                    }
                    index++;
                }
            }
            outputStream.close();
        };
        
        // Write to the output
        return Response.ok(stream)
                .header("Content-Type", "application/zip")
                .header("Content-Disposition", "attachment; filename=\"" + zipFileName + ".zip\"")
                .build();
    }

    /**
     * Find a file with access rights checking.
     *
     * @param fileId File ID
     * @param shareId Share ID
     * @return File
     */
    private File findFile(String fileId, String shareId, PermType requiredPerm) {
        FileDao fileDao = new FileDao();
        File file = fileDao.getFile(fileId);
        if (file == null) {
            throw new NotFoundException();
        }
        checkFileAccessible(shareId, file, requiredPerm);
        return file;
    }


    /**
     * Find a list of files with access rights checking.
     *
     * @param filesIds Files IDs
     * @param requiredPerm Minimum permission the caller must hold on each file's document.
     * @return List<File>
     */
    private List<File> findFiles(List<String> filesIds, PermType requiredPerm) {
        FileDao fileDao = new FileDao();
        List<File> files = fileDao.getFiles(filesIds);
        for (File file : files) {
            checkFileAccessible(null, file, requiredPerm);
        }
        return files;
    }

    /**
     * Check if a file is accessible to the current user.
     *
     * @param shareId Share ID (null for owner-side requests)
     * @param file File being accessed
     * @param requiredPerm Minimum permission the caller must hold on the file's document
     *                     (READ for preview, DOWNLOADABLE for raw download/zip).
     */
    private void checkFileAccessible(String shareId, File file, PermType requiredPerm) {
        if (file.getDocumentId() == null) {
            // It's an orphan file
            if (!file.getUserId().equals(principal.getId())) {
                // But not ours
                throw new ForbiddenClientException();
            }
        } else {
            // Check document accessibility against the requested permission level.
            AclDao aclDao = new AclDao();
            if (!aclDao.checkPermission(file.getDocumentId(), requiredPerm, getTargetIdList(shareId))) {
                throw new ForbiddenClientException();
            }
        }
    }

    /**
     * Build a self-contained HTML page that displays OCR text content with a tiled
     * watermark of "username · timestamp" across the whole viewport. The page is
     * designed to be saved/printed/screenshot-proof: any captured image still carries
     * the viewing user identity and the time of access.
     *
     * @param text       OCR text content (already HTML-escaped before interpolation)
     * @param username   viewing user (server-side, from principal)
     * @param docTitle   parent document title (HTML-escaped)
     * @param fileName   source filename (HTML-escaped)
     * @param timestampMs epoch millis when the page was generated
     * @return complete HTML document as a String
     */
    private String buildWatermarkedTextPage(String text, String username, String docTitle,
                                            String fileName, long timestampMs) {
        String safeUsername = htmlEscape(username);
        String safeDocTitle = htmlEscape(docTitle);
        String safeFileName = htmlEscape(fileName);
        String safeText = htmlEscape(text);
        // Format timestamp as yyyy-MM-dd HH:mm:ss in server local timezone
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(java.util.TimeZone.getDefault());
        String formattedTime = sdf.format(new java.util.Date(timestampMs));
        String watermarkText = safeUsername + " · " + formattedTime;

        // 600 个 div（20 列 × 30 行）网格铺满，与前端 file_content state 同样的视觉效果。
        // 用服务端渲染，访问者即便禁用 JS 也能看到水印。
        StringBuilder lines = new StringBuilder();
        for (int row = 0; row < 30; row++) {
            for (int col = 0; col < 20; col++) {
                int left = col * 280;
                int top = row * 120 - 200;
                lines.append("<div class=\"wm-line\" style=\"left:")
                     .append(left).append("px;top:").append(top).append("px;\">")
                     .append(watermarkText)
                     .append("</div>");
            }
        }

        return "<!DOCTYPE html>\n" +
            "<html lang=\"zh-CN\">\n" +
            "<head>\n" +
            "  <meta charset=\"utf-8\">\n" +
            "  <title>" + safeFileName + " - OCR 文本</title>\n" +
            "  <style>\n" +
            "    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #fff; }\n" +
            "    header { padding: 12px 24px; background: #f5f5f5; border-bottom: 1px solid #ddd; }\n" +
            "    header h1 { margin: 0; font-size: 18px; color: #333; }\n" +
            "    header .meta { color: #888; font-size: 12px; margin-top: 4px; }\n" +
            "    .text-content { position: relative; padding: 24px; min-height: calc(100vh - 80px); }\n" +
            "    pre.content { white-space: pre-wrap; word-wrap: break-word;\n" +
            "                  font-family: 'Courier New', monospace; font-size: 14px;\n" +
            "                  line-height: 1.6; margin: 0; }\n" +
            "    .watermark-overlay { position: absolute; inset: 0; overflow: hidden;\n" +
            "                          pointer-events: none; z-index: 10; }\n" +
            "    .wm-line { position: absolute; transform: rotate(-30deg); transform-origin: 0 0;\n" +
            "               white-space: nowrap; color: #000; opacity: 0.18;\n" +
            "               font-size: 22px; font-weight: 600;\n" +
            "               font-family: Helvetica, Arial, sans-serif; }\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "  <header>\n" +
            "    <h1>OCR 文本预览（带水印 — 截图可追溯）</h1>\n" +
            "    <div class=\"meta\">文档: " + safeDocTitle + " · 文件: " + safeFileName + " · 访问者: " + safeUsername + " · 时间: " + formattedTime + "</div>\n" +
            "  </header>\n" +
            "  <div class=\"text-content\">\n" +
            "    <pre class=\"content\">" + safeText + "</pre>\n" +
            "    <div class=\"watermark-overlay\">" + lines.toString() + "</div>\n" +
            "  </div>\n" +
            "</body>\n" +
            "</html>";
    }

    /**
     * Minimal HTML entity escape for safe interpolation into the watermarked page.
     * We don't need full HTML sanitization here because the text is the user's own
     * OCR text (already author-controlled at upload time) and the metadata fields
     * come from the DB. Better-safe-than-sorry.
     */
    private String htmlEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  out.append("&amp;");  break;
                case '<':  out.append("&lt;");   break;
                case '>':  out.append("&gt;");   break;
                case '"':  out.append("&quot;"); break;
                case '\'': out.append("&#39;");  break;
                default:   out.append(c);
            }
        }
        return out.toString();
    }
}
