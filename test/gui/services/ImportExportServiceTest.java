package gui.services;

import static org.fest.assertions.Assertions.assertThat;
import exceptions.BadRequestException;
import exceptions.ForbiddenException;
import gui.AbstractGuiTest;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import models.ComponentModel;
import models.StudyModel;
import models.workers.ClosedStandaloneWorker;
import models.workers.JatosWorker;
import models.workers.TesterWorker;

import org.junit.Test;

import play.db.jpa.JPA;
import play.mvc.Http;
import play.mvc.Http.MultipartFormData.FilePart;
import services.gui.ImportExportService;
import utils.IOUtils;
import utils.JsonUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import common.Global;

/**
 * Tests ImportExportService
 * 
 * @author Kristian Lange
 */
public class ImportExportServiceTest extends AbstractGuiTest {

	private ImportExportService importExportService;

	@Override
	public void before() throws Exception {
		importExportService = Global.INJECTOR
				.getInstance(ImportExportService.class);
		mockContext();
		// Don't know why, but we have to bind entityManager again
		JPA.bindForCurrentThread(entityManager);
	}

	@Override
	public void after() throws Exception {
		JPA.bindForCurrentThread(null);
	}

	@Test
	public void simpleCheck() {
		int a = 1 + 1;
		assertThat(a).isEqualTo(2);
	}

	@Test
	public void importExistingComponent() throws Exception {
		StudyModel study = importExampleStudy();
		addStudy(study);

		// First component of the study is the one in the component file
		File componentFile = getExampleComponentFile();
		FilePart filePart = new FilePart(ComponentModel.COMPONENT,
				componentFile.getName(), "multipart/form-data", componentFile);

		// Call importComponent()
		ObjectNode jsonNode = importExportService.importComponent(study,
				filePart);
		assertThat(
				jsonNode.get(ImportExportService.COMPONENT_EXISTS).asBoolean())
				.isTrue();
		assertThat(jsonNode.get(ImportExportService.COMPONENT_TITLE).asText())
				.isEqualTo("Hello World");

		// Change properties of first component, so we have something to check
		// later on
		ComponentModel firstComponent = study.getFirstComponent();
		firstComponent = JsonUtils.initializeAndUnproxy(firstComponent);
		firstComponent.setTitle("Changed Title");
		firstComponent.setActive(false);
		firstComponent.setComments("Changed comments");
		firstComponent.setHtmlFilePath("changedHtmlFilePath");
		firstComponent.setJsonData("{}");
		firstComponent.setReloadable(false);
		// We have to set the study again otherwise it's null. Don't know why.
		firstComponent.setStudy(study);
		entityManager.getTransaction().begin();
		componentDao.update(firstComponent);
		entityManager.getTransaction().commit();

		// Call importComponentConfirmed(): Since the imported component is
		// already part of the study (at first position), it will be overwritten
		entityManager.getTransaction().begin();
		importExportService.importComponentConfirmed(study,
				componentFile.getName());
		entityManager.getTransaction().commit();

		// Check that everything in the first component was updated
		ComponentModel updatedComponent = study.getFirstComponent();
		checkHelloWorldComponent(study, updatedComponent);

		// IDs are unchanged
		assertThat(updatedComponent.getId()).isEqualTo(firstComponent.getId());
		assertThat(updatedComponent.getUuid()).isEqualTo(
				firstComponent.getUuid());

		// Clean-up
		if (componentFile.exists()) {
			componentFile.delete();
		}
		removeStudy(study);
	}

	private void checkHelloWorldComponent(StudyModel study,
			ComponentModel updatedComponent) {
		assertThat(updatedComponent.getTitle()).isEqualTo("Hello World");
		assertThat(updatedComponent.getComments()).isEqualTo(
				"This is the most basic component.");
		assertThat(updatedComponent.getHtmlFilePath()).isEqualTo(
				"hello_world.html");
		assertThat(updatedComponent.getJsonData()).isEqualTo(null);
		assertThat(updatedComponent.getStudy()).isEqualTo(study);
		assertThat(updatedComponent.getTitle()).isEqualTo("Hello World");
		assertThat(updatedComponent.isActive()).isTrue();
		assertThat(updatedComponent.isReloadable()).isTrue();
	}

	@Test
	public void importNewComponent() throws NoSuchAlgorithmException,
			IOException {
		StudyModel study = importExampleStudy();
		addStudy(study);
		File componentFile = getExampleComponentFile();
		FilePart filePart = new FilePart(ComponentModel.COMPONENT,
				componentFile.getName(), "multipart/form-data", componentFile);

		// Since the first component of the study is the one in the component
		// file, remove it
		entityManager.getTransaction().begin();
		componentDao.remove(study, study.getFirstComponent());
		entityManager.getTransaction().commit();

		// Call importComponent()
		ObjectNode jsonNode = importExportService.importComponent(study,
				filePart);
		assertThat(
				jsonNode.get(ImportExportService.COMPONENT_EXISTS).asBoolean())
				.isFalse();
		assertThat(jsonNode.get(ImportExportService.COMPONENT_TITLE).asText())
				.isEqualTo("Hello World");

		// Call importComponentConfirmed(): The new component will be put on the
		// end of study's component list
		entityManager.getTransaction().begin();
		importExportService.importComponentConfirmed(study,
				componentFile.getName());
		entityManager.getTransaction().commit();

		// Check all properties of the last component
		ComponentModel newComponent = study.getLastComponent();
		checkHelloWorldComponent(study, newComponent);
		assertThat(newComponent.getId()).isNotNull();
		assertThat(newComponent.getUuid()).isEqualTo(
				"ae05b118-7d9a-4e5b-bd6c-8109d42e371e");

		// Clean-up
		if (componentFile.exists()) {
			componentFile.delete();
		}
		removeStudy(study);
	}

	@Test
	public void importNewStudy() throws IOException, ForbiddenException,
			BadRequestException {
		File studyFile = getExampleStudyFile();
		FilePart filePart = new FilePart(StudyModel.STUDY, studyFile.getName(),
				"multipart/form-data", studyFile);
		ObjectNode jsonNode = importExportService.importStudy(admin,
				filePart.getFile());
		assertThat(jsonNode.get(ImportExportService.STUDY_EXISTS).asBoolean())
				.isFalse();
		assertThat(jsonNode.get(ImportExportService.STUDY_TITLE).asText())
				.isEqualTo("Basic Example Study");
		assertThat(jsonNode.get(ImportExportService.DIR_EXISTS).asBoolean())
				.isFalse();
		assertThat(
				jsonNode.get(ImportExportService.DIR_PATH).asText() + "."
						+ IOUtils.ZIP_FILE_SUFFIX).isEqualTo(
				studyFile.getName());

		// Call importStudyConfirmed(): Since this study is new, the overwrite
		// parameters don't matter
		ObjectNode node = JsonUtils.OBJECTMAPPER.createObjectNode();
		node.put(ImportExportService.STUDYS_PROPERTIES_CONFIRM, true);
		node.put(ImportExportService.STUDYS_DIR_CONFIRM, true);
		entityManager.getTransaction().begin();
		importExportService.importStudyConfirmed(admin, node);
		entityManager.getTransaction().commit();

		List<StudyModel> studyList = studyDao.findAll();
		assertThat(studyList.size() == 1).isTrue();
		StudyModel study = studyList.get(0);
		checkPropertiesOfBasicExampleStudy(study);
		checkAssetsOfBasicExampleStudy(study, "basic_example_study");

		// Clean up
		removeStudy(study);
	}

	private void checkPropertiesOfBasicExampleStudy(StudyModel study) {
		assertThat(study.getAllowedWorkerList()).containsOnly(
				JatosWorker.WORKER_TYPE, ClosedStandaloneWorker.WORKER_TYPE,
				TesterWorker.WORKER_TYPE);
		assertThat(study.getComponentList().size() == 8).isTrue();
		assertThat(study.getComponent(1).getTitle()).isEqualTo("Hello World");
		assertThat(study.getLastComponent().getTitle())
				.isEqualTo("Quit button");
		assertThat(study.getDate()).isNull();
		assertThat(study.getDescription()).isEqualTo(
				"A couple of sample components.");
		assertThat(study.getId()).isPositive();
		assertThat(study.getJsonData().contains("\"totalStudySlides\" : 17"))
				.isTrue();
		assertThat(study.getMemberList().contains(admin)).isTrue();
		assertThat(study.getTitle()).isEqualTo("Basic Example Study");
		assertThat(study.getUuid()).isEqualTo(
				"5c85bd82-0258-45c6-934a-97ecc1ad6617");
	}

	private void checkAssetsOfBasicExampleStudy(StudyModel study, String dirName)
			throws IOException {
		assertThat(study.getDirName()).isEqualTo(dirName);
		assertThat(IOUtils.checkStudyAssetsDirExists(study.getDirName()))
				.isTrue();

		// Check the number of files and directories in the study assets
		String[] fileList = IOUtils.getStudyAssetsDir(study.getDirName())
				.list();
		assertThat(fileList.length == 10);
	}

	@Test
	public void importStudyOverwritePropertiesAndAssets()
			throws NoSuchAlgorithmException, IOException, ForbiddenException,
			BadRequestException {
		// Import study, so we have something to overwrite
		StudyModel study = importExampleStudy();
		alterStudy(study);
		addStudy(study);

		entityManager.getTransaction().begin();
		studyService.renameStudyAssetsDir(study, "changed_dirname");
		entityManager.getTransaction().commit();

		File studyFile = getExampleStudyFile();

		FilePart filePart = new FilePart(StudyModel.STUDY, studyFile.getName(),
				"multipart/form-data", studyFile);
		ObjectNode jsonNode = importExportService.importStudy(admin,
				filePart.getFile());
		assertThat(jsonNode.get(ImportExportService.STUDY_EXISTS).asBoolean())
				.isTrue();
		assertThat(jsonNode.get(ImportExportService.STUDY_TITLE).asText())
				.isEqualTo("Basic Example Study");
		assertThat(jsonNode.get(ImportExportService.DIR_EXISTS).asBoolean())
				.isFalse();
		assertThat(
				jsonNode.get(ImportExportService.DIR_PATH).asText() + "."
						+ IOUtils.ZIP_FILE_SUFFIX).isEqualTo(
				studyFile.getName());

		// Call importStudyConfirmed(): Allow properties and assets to be
		// overwritten
		ObjectNode node = JsonUtils.OBJECTMAPPER.createObjectNode();
		node.put(ImportExportService.STUDYS_PROPERTIES_CONFIRM, true);
		node.put(ImportExportService.STUDYS_DIR_CONFIRM, true);
		entityManager.getTransaction().begin();
		importExportService.importStudyConfirmed(admin, node);
		entityManager.getTransaction().commit();

		List<StudyModel> studyList = studyDao.findAll();
		assertThat(studyList.size() == 1).isTrue();
		StudyModel importedStudy = studyList.get(0);
		checkPropertiesOfBasicExampleStudy(importedStudy);
		checkAssetsOfBasicExampleStudy(study, "basic_example_study");

		// Clean up
		removeStudy(study);
	}

	@Test
	public void importStudyOverwritePropertiesNotAssets()
			throws NoSuchAlgorithmException, IOException, ForbiddenException,
			BadRequestException {
		// Import study, so we have something to overwrite
		StudyModel study = importExampleStudy();
		alterStudy(study);
		addStudy(study);

		entityManager.getTransaction().begin();
		studyService.renameStudyAssetsDir(study, "changed_dirname");
		entityManager.getTransaction().commit();

		File studyFile = getExampleStudyFile();

		FilePart filePart = new FilePart(StudyModel.STUDY, studyFile.getName(),
				"multipart/form-data", studyFile);
		ObjectNode jsonNode = importExportService.importStudy(admin,
				filePart.getFile());
		assertThat(jsonNode.get(ImportExportService.STUDY_EXISTS).asBoolean())
				.isTrue();
		assertThat(jsonNode.get(ImportExportService.STUDY_TITLE).asText())
				.isEqualTo("Basic Example Study");
		assertThat(jsonNode.get(ImportExportService.DIR_EXISTS).asBoolean())
				.isFalse();
		assertThat(
				jsonNode.get(ImportExportService.DIR_PATH).asText() + "."
						+ IOUtils.ZIP_FILE_SUFFIX).isEqualTo(
				studyFile.getName());

		// Call importStudyConfirmed(): Allow properties but not assets to be
		// overwritten
		ObjectNode node = JsonUtils.OBJECTMAPPER.createObjectNode();
		node.put(ImportExportService.STUDYS_PROPERTIES_CONFIRM, true);
		node.put(ImportExportService.STUDYS_DIR_CONFIRM, false);
		entityManager.getTransaction().begin();
		importExportService.importStudyConfirmed(admin, node);
		entityManager.getTransaction().commit();

		List<StudyModel> studyList = studyDao.findAll();
		assertThat(studyList.size() == 1).isTrue();
		StudyModel importedStudy = studyList.get(0);
		checkPropertiesOfBasicExampleStudy(importedStudy);
		checkAssetsOfBasicExampleStudy(study, "changed_dirname");

		// Clean up
		removeStudy(study);
	}

	@Test
	public void importStudyOverwriteAssetsNotProperties()
			throws NoSuchAlgorithmException, IOException, ForbiddenException,
			BadRequestException {
		// Import study, so we have something to overwrite
		StudyModel study = importExampleStudy();
		alterStudy(study);
		addStudy(study);

		entityManager.getTransaction().begin();
		studyService.renameStudyAssetsDir(study, "changed_dirname");
		entityManager.getTransaction().commit();

		File studyFile = getExampleStudyFile();

		FilePart filePart = new FilePart(StudyModel.STUDY, studyFile.getName(),
				"multipart/form-data", studyFile);
		ObjectNode jsonNode = importExportService.importStudy(admin,
				filePart.getFile());
		assertThat(jsonNode.get(ImportExportService.STUDY_EXISTS).asBoolean())
				.isTrue();
		assertThat(jsonNode.get(ImportExportService.STUDY_TITLE).asText())
				.isEqualTo("Basic Example Study");
		assertThat(jsonNode.get(ImportExportService.DIR_EXISTS).asBoolean())
				.isFalse();
		assertThat(
				jsonNode.get(ImportExportService.DIR_PATH).asText() + "."
						+ IOUtils.ZIP_FILE_SUFFIX).isEqualTo(
				studyFile.getName());

		// Call importStudyConfirmed(): Allow properties but not assets to be
		// overwritten
		ObjectNode node = JsonUtils.OBJECTMAPPER.createObjectNode();
		node.put(ImportExportService.STUDYS_PROPERTIES_CONFIRM, false);
		node.put(ImportExportService.STUDYS_DIR_CONFIRM, true);
		entityManager.getTransaction().begin();
		importExportService.importStudyConfirmed(admin, node);
		entityManager.getTransaction().commit();

		assertThat(study.getAllowedWorkerList()).containsOnly(
				JatosWorker.WORKER_TYPE);
		assertThat(study.getComponentList().size() == 7).isTrue();
		assertThat(study.getComponent(1).getTitle()).isEqualTo(
				"Show JSON data ");
		assertThat(study.getLastComponent().getTitle()).isEqualTo(
				"Changed title");
		assertThat(study.getDate()).isNull();
		assertThat(study.getDescription()).isEqualTo("Changed description");
		assertThat(study.getId()).isPositive();
		assertThat(study.getJsonData()).isEqualTo("{ }");
		assertThat(study.getMemberList().contains(admin)).isTrue();
		assertThat(study.getTitle()).isEqualTo("Changed Title");
		assertThat(study.getUuid()).isEqualTo(
				"5c85bd82-0258-45c6-934a-97ecc1ad6617");

		checkAssetsOfBasicExampleStudy(study, "changed_dirname");

		// Clean up
		removeStudy(study);
	}

	private void alterStudy(StudyModel study) {
		study.removeAllowedWorker(ClosedStandaloneWorker.WORKER_TYPE);
		study.removeAllowedWorker(TesterWorker.WORKER_TYPE);
		study.getComponentList().remove(0);
		study.getLastComponent().setTitle("Changed title");
		study.setDescription("Changed description");
		study.setJsonData("{}");
		study.setTitle("Changed Title");
	}

	@Test
	public void checkCreateStudyExportZipFile()
			throws NoSuchAlgorithmException, IOException, ForbiddenException {
		StudyModel study = importExampleStudy();
		addStudy(study);

		File studyFile = importExportService.createStudyExportZipFile(study);

		JsonNode jsonNode = importExportService.importStudy(admin, studyFile);
		assertThat(jsonNode.get(ImportExportService.STUDY_EXISTS).asBoolean())
				.isTrue();
		assertThat(jsonNode.get(ImportExportService.STUDY_TITLE).asText())
				.isEqualTo("Basic Example Study");
		assertThat(jsonNode.get(ImportExportService.DIR_EXISTS).asBoolean())
				.isTrue();
		assertThat(jsonNode.get(ImportExportService.DIR_PATH).asText())
				.isNotEmpty();

		String studyFileName = Http.Context.current.get().session()
				.get(ImportExportService.SESSION_UNZIPPED_STUDY_DIR);
		assertThat(studyFileName).isNotEmpty();

		// Clean up
		removeStudy(study);
	}
}
