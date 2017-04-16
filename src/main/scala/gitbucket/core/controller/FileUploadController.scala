package gitbucket.core.controller

import gitbucket.core.model.Account
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.service.{AccountService, RepositoryService, ReleaseService}
import gitbucket.core.servlet.Database
import gitbucket.core.util._
import gitbucket.core.util.SyntaxSugars._
import gitbucket.core.util.Directory._
import gitbucket.core.util.Implicits._
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.lib.{Constants, FileMode}
import org.scalatra._
import org.scalatra.servlet.{FileItem, FileUploadSupport, MultipartConfig}
import org.apache.commons.io.{FileUtils, IOUtils}

/**
 * Provides Ajax based file upload functionality.
 *
 * This servlet saves uploaded file.
 */
class FileUploadController extends ScalatraServlet
  with FileUploadSupport
  with RepositoryService
  with AccountService
  with ReleaseService{

  configureMultipartHandling(MultipartConfig(maxFileSize = Some(3 * 1024 * 1024)))

  post("/image"){
    execute({ (file, fileId) =>
      FileUtils.writeByteArrayToFile(new java.io.File(getTemporaryDir(session.getId), fileId), file.get)
      session += Keys.Session.Upload(fileId) -> file.name
    }, FileUtil.isImage)
  }

  post("/file/:owner/:repository"){
    execute({ (file, fileId) =>
      FileUtils.writeByteArrayToFile(new java.io.File(
        getAttachedDir(params("owner"), params("repository")),
        fileId + "." + FileUtil.getExtension(file.getName)), file.get)
    }, FileUtil.isUploadableType)
  }

  post("/wiki/:owner/:repository"){
    // Don't accept not logged-in users
    session.get(Keys.Session.LoginAccount).collect { case loginAccount: Account =>
      val owner      = params("owner")
      val repository = params("repository")

      // Check whether logged-in user is collaborator
      onlyWikiEditable(owner, repository, loginAccount){
        execute({ (file, fileId) =>
          val fileName = file.getName
          LockUtil.lock(s"${owner}/${repository}/wiki") {
            using(Git.open(Directory.getWikiRepositoryDir(owner, repository))) { git =>
              val builder  = DirCache.newInCore.builder()
              val inserter = git.getRepository.newObjectInserter()
              val headId   = git.getRepository.resolve(Constants.HEAD + "^{commit}")

              if(headId != null){
                JGitUtil.processTree(git, headId){ (path, tree) =>
                  if(path != fileName){
                    builder.add(JGitUtil.createDirCacheEntry(path, tree.getEntryFileMode, tree.getEntryObjectId))
                  }
                }
              }

              val bytes = IOUtils.toByteArray(file.getInputStream)
              builder.add(JGitUtil.createDirCacheEntry(fileName, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, bytes)))
              builder.finish()

              val newHeadId = JGitUtil.createNewCommit(git, inserter, headId, builder.getDirCache.writeTree(inserter),
                Constants.HEAD, loginAccount.userName, loginAccount.mailAddress, s"Uploaded ${fileName}")

              fileName
            }
          }
        }, FileUtil.isUploadableType)
      }
    } getOrElse BadRequest()
  }

  post("/release/:owner/:repository/:id"){
    session.get(Keys.Session.LoginAccount).collect { case loginAccount: Account =>
      val owner = params("owner")
      val repository = params("repository")
      val releaseId = params("id").toInt
      val release = getRelease(owner, repository, releaseId)
      execute({ (file, fileId) =>
        val fileName = file.getName
        release.map { rel =>
          createReleaseAsset(owner, repository, releaseId, fileId, fileName, file.size, loginAccount)
          FileUtils.writeByteArrayToFile(new java.io.File(
            getReleaseFilesDir(owner, repository) + s"/${rel.tag}",
            fileId), file.get)
          fileName
        }
      }, (_ => true))
    }.getOrElse(BadRequest())
  }

  post("/import") {
    import JDBCUtil._
    session.get(Keys.Session.LoginAccount).collect { case loginAccount: Account if loginAccount.isAdmin =>
      execute({ (file, fileId) =>
        request2Session(request).conn.importAsSQL(file.getInputStream)
      }, _ => true)
    }
    redirect("/admin/data")
  }

  private def onlyWikiEditable(owner: String, repository: String, loginAccount: Account)(action: => Any): Any = {
    implicit val session = Database.getSession(request)
    getRepository(owner, repository) match {
      case Some(x) => x.repository.options.wikiOption match {
        case "ALL"     if !x.repository.isPrivate => action
        case "PUBLIC"  if hasGuestRole(owner, repository, Some(loginAccount)) => action
        case "PRIVATE" if hasDeveloperRole(owner, repository, Some(loginAccount)) => action
        case _  => BadRequest()
      }
      case None => BadRequest()
    }
  }

  private def execute(f: (FileItem, String) => Unit, mimeTypeChcker: (String) => Boolean) = fileParams.get("file") match {
    case Some(file) if(mimeTypeChcker(file.name)) =>
      defining(FileUtil.generateFileId){ fileId =>
        f(file, fileId)
        Ok(fileId)
      }
    case _ => BadRequest()
  }

}
